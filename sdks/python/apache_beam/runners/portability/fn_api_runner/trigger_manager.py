#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
import collections
import logging

import typing
from collections import defaultdict

from apache_beam import typehints
from apache_beam.coders import PickleCoder
from apache_beam.coders import StrUtf8Coder
from apache_beam.coders import TupleCoder
from apache_beam.coders import VarIntCoder
from apache_beam.coders.coders import IntervalWindowCoder
from apache_beam.transforms import DoFn
from apache_beam.transforms.core import Windowing
from apache_beam.transforms.trigger import AccumulationMode
from apache_beam.transforms.trigger import TriggerContext
from apache_beam.transforms.trigger import _CombiningValueStateTag
from apache_beam.transforms.trigger import _StateTag
from apache_beam.transforms.userstate import AccumulatingRuntimeState
from apache_beam.transforms.userstate import BagRuntimeState
from apache_beam.transforms.userstate import BagStateSpec
from apache_beam.transforms.userstate import CombiningValueStateSpec
from apache_beam.transforms.userstate import RuntimeTimer
from apache_beam.transforms.userstate import SetRuntimeState
from apache_beam.transforms.userstate import SetStateSpec
from apache_beam.transforms.userstate import TimerSpec
from apache_beam.transforms.timeutil import TimeDomain
from apache_beam.transforms.userstate import on_timer
from apache_beam.transforms.window import BoundedWindow
from apache_beam.transforms.window import GlobalWindow
from apache_beam.transforms.window import TimestampedValue
from apache_beam.transforms.window import WindowFn
from apache_beam.typehints import TypeCheckError
from apache_beam.utils import windowed_value
from apache_beam.utils.timestamp import MIN_TIMESTAMP
from apache_beam.utils.windowed_value import WindowedValue

_LOGGER = logging.getLogger(__name__)
_LOGGER.setLevel(logging.DEBUG)

K = typing.TypeVar('K')
V = typing.TypeVar('V')


class ReifyWindows(DoFn):
  """Receives KV pairs, and wraps the values into WindowedValues."""
  def process(
      self, element, window=DoFn.WindowParam, timestamp=DoFn.TimestampParam):
    try:
      k, v = element
    except TypeError:
      raise TypeCheckError(
          'Input to GroupByKey must be a PCollection with '
          'elements compatible with KV[A, B]')

    yield (k, windowed_value.WindowedValue(v, timestamp, [window]))


class _GroupBundlesByKey(DoFn):
  def start_bundle(self):
    self.keys = defaultdict(list)

  def process(self, element):
    key, windowed_value = element
    self.keys[key].append(windowed_value)

  def finish_bundle(self):
    for k, vals in self.keys.items():
      yield windowed_value.WindowedValue((k, vals),
                                         MIN_TIMESTAMP, [GlobalWindow()])


def read_watermark(watermark_state):
  try:
    return watermark_state.read()
  except ValueError:
    watermark_state.add(MIN_TIMESTAMP)
    return watermark_state.read()


class TriggerMergeContext(WindowFn.MergeContext):
  def __init__(
      self, all_windows, context: 'FnRunnerStatefulTriggerContext', windowing):
    super(TriggerMergeContext, self).__init__(all_windows)
    self.trigger_context = context
    self.windowing = windowing
    self.merged_away = {}

  def merge(self, to_be_merged, merge_result):
    _LOGGER.debug("Merging %s into %s", to_be_merged, merge_result)
    self.trigger_context.merge_state(to_be_merged, merge_result)
    for window in to_be_merged:
      if window != merge_result:
        self.merged_away[window] = merge_result
        # Clear state associated with PaneInfo since it is
        # not preserved across merges.
        self.trigger_context.for_window(window).clear_state(None)
    self.windowing.triggerfn.on_merge(
        to_be_merged,
        merge_result,
        self.trigger_context.for_window(merge_result))


@typehints.with_input_types(
    typing.Tuple[K, typing.Iterable[windowed_value.WindowedValue]])
@typehints.with_output_types(
    typing.Tuple[K, typing.Iterable[windowed_value.WindowedValue]])
class GeneralTriggerManagerDoFn(DoFn):
  """A trigger manager that supports all windowing / triggering cases.

  This implements a DoFn that manages triggering in a per-key basis. All
  elements for a single key are processed together. Per-key state holds data
  related to all windows.
  """

  KNOWN_WINDOWS = SetStateSpec('known_windows', IntervalWindowCoder())
  FINISHED_WINDOWS = SetStateSpec('finished_windows', IntervalWindowCoder())
  LAST_KNOWN_TIME = CombiningValueStateSpec('last_known_time', combine_fn=max)
  LAST_KNOWN_WATERMARK = CombiningValueStateSpec(
      'last_known_watermark', combine_fn=max)

  # TODO(pabloem) Add check for finished windows
  # TODO(pabloem) What's the coder for the elements/keys here?
  WINDOW_ELEMENT_PAIRS = BagStateSpec(
      'element_bag', TupleCoder([IntervalWindowCoder(), PickleCoder()]))
  WINDOW_TAG_VALUES = BagStateSpec(
      'per_window_per_tag_value_state',
      TupleCoder([IntervalWindowCoder(), StrUtf8Coder(), VarIntCoder()]))

  PROCESSING_TIME_TIMER = TimerSpec(
      'processing_time_timer', TimeDomain.REAL_TIME)
  WATERMARK_TIMER = TimerSpec('watermark_timer', TimeDomain.WATERMARK)

  def __init__(self, windowing: Windowing):
    self.windowing = windowing

  def process(
      self,
      element: typing.Tuple[K, typing.Iterable[windowed_value.WindowedValue]],
      element_bag: BagRuntimeState = DoFn.StateParam(WINDOW_ELEMENT_PAIRS),
      time_state: AccumulatingRuntimeState = DoFn.StateParam(LAST_KNOWN_TIME),
      watermark_state: AccumulatingRuntimeState = DoFn.StateParam(
          LAST_KNOWN_WATERMARK),
      window_tag_values: BagRuntimeState = DoFn.StateParam(WINDOW_TAG_VALUES),
      windows_state: SetRuntimeState = DoFn.StateParam(KNOWN_WINDOWS),
      finished_windows_state: SetRuntimeState = DoFn.StateParam(
          FINISHED_WINDOWS),
      processing_time_timer=DoFn.TimerParam(PROCESSING_TIME_TIMER),
      watermark_timer=DoFn.TimerParam(WATERMARK_TIMER)):
    context = FnRunnerStatefulTriggerContext(
        processing_time_timer=processing_time_timer,
        watermark_timer=watermark_timer,
        current_time_state=time_state,
        watermark_state=watermark_state,
        elements_bag_state=element_bag,
        window_tag_values_bag_state=window_tag_values,
        finished_windows_state=finished_windows_state)
    key, windowed_values = element
    watermark = read_watermark(watermark_state)

    windows_to_elements = collections.defaultdict(list)
    for wv in windowed_values:
      for window in wv.windows:
        # ignore expired windows
        if watermark > window.end + self.windowing.allowed_lateness:
          continue
        if window in finished_windows_state.read():
          continue
        windows_to_elements[window].append(
            TimestampedValue(wv.value, wv.timestamp))

    # Processing merging of windows
    old_windows = set(windows_state.read())
    all_windows = old_windows.union(list(windows_to_elements))
    if all_windows != old_windows:
      merge_context = TriggerMergeContext(all_windows, context, self.windowing)
      self.windowing.windowfn.merge(merge_context)

      merged_windows_to_elements = collections.defaultdict(list)
      for window, values in windows_to_elements.items():
        while window in merge_context.merged_away:
          window = merge_context.merged_away[window]
        merged_windows_to_elements[window].extend(values)
      windows_to_elements = merged_windows_to_elements

    if old_windows != all_windows:
      for w in windows_to_elements:
        windows_state.add(w)
    # Done processing merging of windows

    seen_windows = set()
    for w in windows_to_elements:
      window_context = context.for_window(w)
      seen_windows.add(w)
      for value_w_timestamp in windows_to_elements[w]:
        _LOGGER.debug(value_w_timestamp)
        element_bag.add((w, value_w_timestamp))
        self.windowing.triggerfn.on_element(windowed_values, w, window_context)

    return self._trigger_fire(
        key, TimeDomain.WATERMARK, watermark, None, context, seen_windows)

  def finish_bundle(self):
    pass

  def _trigger_fire(
      self,
      key: K,
      time_domain,
      timestamp,
      timer_tag: str,
      context: 'FnRunnerStatefulTriggerContext',
      windows_of_interest=None):
    windows_to_elements = context.windows_to_elements_map()
    context.elements_bag_state.clear()

    fired_windows = set()
    finished_windows = set()
    _LOGGER.debug(
        '%s - tag %s - timestamp %s', time_domain, timer_tag, timestamp)
    for w, elems in windows_to_elements.items():
      if windows_of_interest is not None and w not in windows_of_interest:
        # windows_of_interest=None means that we care about all windows.
        # If we care only about some windows, and this window is not one of
        # them, then we do not intend to fire this window.
        continue
      window_context = context.for_window(w)
      if self.windowing.triggerfn.should_fire(time_domain,
                                              timestamp,
                                              w,
                                              window_context):
        finished = self.windowing.triggerfn.on_fire(
            timestamp, w, window_context)
        _LOGGER.debug('Firing on window %s. Finished: %s', w, finished)
        fired_windows.add(w)
        if finished:
          context.finished_windows_state.add(w)
          finished_windows.add(w)
        # TODO(pabloem): Format the output: e.g. pane info
        elems = [WindowedValue(e.value, e.timestamp, (w, )) for e in elems]
        yield (key, elems)

    # Add elements that were not fired back into state.
    for w, elems in windows_to_elements.items():
      for e in elems:
        if (w in finished_windows or
            (w in fired_windows and
             self.windowing.accumulation_mode == AccumulationMode.DISCARDING)):
          continue
        context.elements_bag_state.add((w, e))

  @on_timer(PROCESSING_TIME_TIMER)
  def processing_time_trigger(
      self,
      key=DoFn.KeyParam,
      timer_tag=DoFn.DynamicTimerTagParam,
      timestamp=DoFn.TimestampParam,
      processing_time_state=DoFn.StateParam(LAST_KNOWN_TIME),
      element_bag=DoFn.StateParam(WINDOW_ELEMENT_PAIRS),
      processing_time_timer=DoFn.TimerParam(PROCESSING_TIME_TIMER),
      window_tag_values: BagRuntimeState = DoFn.StateParam(WINDOW_TAG_VALUES),
      finished_windows_state: SetRuntimeState = DoFn.StateParam(
          FINISHED_WINDOWS),
      watermark_timer=DoFn.TimerParam(WATERMARK_TIMER)):
    context = FnRunnerStatefulTriggerContext(
        processing_time_timer=processing_time_timer,
        watermark_timer=watermark_timer,
        current_time_state=processing_time_state,
        watermark_state=None,
        elements_bag_state=element_bag,
        window_tag_values_bag_state=window_tag_values,
        finished_windows_state=finished_windows_state)
    result = self._trigger_fire(
        key, TimeDomain.REAL_TIME, timestamp, timer_tag, context)
    processing_time_state.add(timestamp)
    return result

  @on_timer(WATERMARK_TIMER)
  def watermark_trigger(
      self,
      key=DoFn.KeyParam,
      timer_tag=DoFn.DynamicTimerTagParam,
      timestamp=DoFn.TimestampParam,
      watermark_state=DoFn.StateParam(LAST_KNOWN_WATERMARK),
      element_bag=DoFn.StateParam(WINDOW_ELEMENT_PAIRS),
      processing_time_timer=DoFn.TimerParam(PROCESSING_TIME_TIMER),
      window_tag_values: BagRuntimeState = DoFn.StateParam(WINDOW_TAG_VALUES),
      finished_windows_state: SetRuntimeState = DoFn.StateParam(
          FINISHED_WINDOWS),
      watermark_timer=DoFn.TimerParam(WATERMARK_TIMER)):
    context = FnRunnerStatefulTriggerContext(
        processing_time_timer=processing_time_timer,
        watermark_timer=watermark_timer,
        current_time_state=None,
        watermark_state=watermark_state,
        elements_bag_state=element_bag,
        window_tag_values_bag_state=window_tag_values,
        finished_windows_state=finished_windows_state)
    result = self._trigger_fire(
        key, TimeDomain.WATERMARK, timestamp, timer_tag, context)
    watermark_state.add(timestamp)
    return result


class FnRunnerStatefulTriggerContext(TriggerContext):
  def __init__(
      self,
      processing_time_timer: RuntimeTimer,
      watermark_timer: RuntimeTimer,
      current_time_state: AccumulatingRuntimeState,
      watermark_state: AccumulatingRuntimeState,
      elements_bag_state: BagRuntimeState,
      window_tag_values_bag_state: BagRuntimeState,
      finished_windows_state: SetRuntimeState):
    self.timers = {
        TimeDomain.REAL_TIME: processing_time_timer,
        TimeDomain.WATERMARK: watermark_timer
    }
    self.current_times = {
        TimeDomain.REAL_TIME: current_time_state,
        TimeDomain.WATERMARK: watermark_state
    }
    self.elements_bag_state = elements_bag_state
    self.window_tag_values_bag_state = window_tag_values_bag_state
    self.finished_windows_state = finished_windows_state

  def windows_to_elements_map(
      self
  ) -> typing.Dict[BoundedWindow, typing.List[windowed_value.WindowedValue]]:
    window_element_pairs: typing.Iterable[typing.Tuple[
        BoundedWindow,
        windowed_value.WindowedValue]] = self.elements_bag_state.read()
    result = {}
    for w, e in window_element_pairs:
      if w not in result:
        result[w] = []
      result[w].append(e)
    return result

  def for_window(self, window):
    return PerWindowTriggerContext(window, self)

  def get_current_time(self):
    return self.current_times[TimeDomain.REAL_TIME].read()

  def set_timer(self, name, time_domain, timestamp):
    _LOGGER.debug('Setting timer (%s, %s) at %s', time_domain, name, timestamp)
    self.timers[time_domain].set(timestamp, dynamic_timer_tag=name)

  def clear_timer(self, name, time_domain):
    _LOGGER.debug('Clearing timer (%s, %s)', time_domain, name)
    self.timers[time_domain].clear(dynamic_timer_tag=name)

  def merge_state(self, to_be_merged, merge_result):
    all_triplets = self.window_tag_values_bag_state.read()
    # Collect all the triplets for the window we are merging away, and tag them
    # with the new window (merge_result).
    merging_away_triplets = [(merge_result, t[1], t[2]) for t in all_triplets
                             if t[0] in to_be_merged]

    # Collect all of the other triplets, and joining them with the newly-tagged
    # set of triplets.
    resulting_triplets = [
        t for t in all_triplets if t[0] not in to_be_merged
    ] + merging_away_triplets
    self.window_tag_values_bag_state.clear()
    for t in resulting_triplets:
      self.window_tag_values_bag_state.add(t)

    # Merge also element-window pairs
    all_elements = self.elements_bag_state.read()
    resulting_elements = [
        (merge_result if e[0] in to_be_merged else e[0], e[1])
        for e in all_elements
    ]
    self.elements_bag_state.clear()
    for e in resulting_elements:
      self.elements_bag_state.add(e)

  def add_state(self, tag, value):
    # State can only be kept in per-window context, so this is not implemented.
    raise NotImplementedError('unimplemented')

  def get_state(self, tag):
    # State can only be kept in per-window context, so this is not implemented.
    raise NotImplementedError('unimplemented')

  def clear_state(self, tag):
    # State can only be kept in per-window context, so this is not implemented.
    raise NotImplementedError('unimplemented')


class PerWindowTriggerContext(TriggerContext):
  def __init__(self, window, parent: FnRunnerStatefulTriggerContext):
    self.window = window
    self.parent = parent

  def get_current_time(self):
    return self.parent.get_current_time()

  def set_timer(self, name, time_domain, timestamp):
    self.parent.set_timer(name, time_domain, timestamp)

  def clear_timer(self, name, time_domain):
    _LOGGER.debug('Clearing timer (%s, %s)', time_domain, name)
    self.parent.clear_timer(name, time_domain)

  def add_state(self, tag: _StateTag, value):
    assert isinstance(tag, _CombiningValueStateTag)
    # Used to count:
    #   1) number of elements in a window ('count')
    #   2) number of triggers matched individually ('index')
    #   3) whether the watermark has passed end of window ('is_late')
    self.parent.window_tag_values_bag_state.add((self.window, tag.tag, value))

  def get_state(self, tag: _StateTag):
    assert isinstance(tag, _CombiningValueStateTag)
    # Used to count:
    #   1) number of elements in a window ('count')
    #   2) number of triggers matched individually ('index')
    #   3) whether the watermark has passed end of window ('is_late')
    all_triplets = self.parent.window_tag_values_bag_state.read()
    relevant_triplets = [
        t for t in all_triplets if t[0] == self.window and t[1] == tag.tag
    ]
    return tag.combine_fn.apply(relevant_triplets)

  def clear_state(self, tag: _StateTag):
    if tag is None:
      matches = lambda x: True
    else:
      matches = lambda x: x == tag.tag
    all_triplets = list(self.parent.window_tag_values_bag_state.read())
    _LOGGER.debug('Tag: %s | All triplis: %s', tag, all_triplets)
    remaining_triplets = [
        t for t in all_triplets if not (t[0] == self.window and matches(t[1]))
    ]
    _LOGGER.debug('Remaining triplis: %s', remaining_triplets)
    self.parent.window_tag_values_bag_state.clear()
    for t in remaining_triplets:
      self.parent.window_tag_values_bag_state.add(t)
