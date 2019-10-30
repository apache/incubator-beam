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

"""Provides TestStream for verifying streaming runner semantics.

For internal use only; no backwards-compatibility guarantees.
"""
from __future__ import absolute_import

from abc import ABCMeta
from abc import abstractmethod
from builtins import object
from collections import namedtuple
from functools import total_ordering

from future.utils import with_metaclass

import apache_beam as beam

from apache_beam import coders
from apache_beam import core
from apache_beam import pvalue
from apache_beam.transforms import PTransform
from apache_beam.transforms import window
from apache_beam.transforms.window import TimestampedValue
from apache_beam.utils import timestamp
from apache_beam.utils.windowed_value import WindowedValue

__all__ = [
    'Event',
    'ElementEvent',
    'WatermarkEvent',
    'ProcessingTimeEvent',
    'TestStream',
    ]


@total_ordering
class Event(with_metaclass(ABCMeta, object)):
  """Test stream event to be emitted during execution of a TestStream."""

  @abstractmethod
  def __eq__(self, other):
    raise NotImplementedError

  @abstractmethod
  def __hash__(self):
    raise NotImplementedError

  @abstractmethod
  def __lt__(self, other):
    raise NotImplementedError

  def __ne__(self, other):
    # TODO(BEAM-5949): Needed for Python 2 compatibility.
    return not self == other


class ElementEvent(Event):
  """Element-producing test stream event."""

  def __init__(self, timestamped_values, tag=None):
    self.timestamped_values = timestamped_values
    self.tag = tag

  def __eq__(self, other):
    return (self.timestamped_values == other.timestamped_values and
            self.tag == other.tag)

  def __hash__(self):
    return hash(self.timestamped_values)

  def __lt__(self, other):
    return self.timestamped_values < other.timestamped_values


class WatermarkEvent(Event):
  """Watermark-advancing test stream event."""

  def __init__(self, new_watermark, tag=None):
    self.new_watermark = timestamp.Timestamp.of(new_watermark)
    self.tag = tag

  def __eq__(self, other):
    return self.new_watermark == other.new_watermark and self.tag == other.tag

  def __hash__(self):
    return hash(self.new_watermark)

  def __lt__(self, other):
    return self.new_watermark < other.new_watermark


class ProcessingTimeEvent(Event):
  """Processing time-advancing test stream event."""

  def __init__(self, advance_by):
    self.advance_by = timestamp.Duration.of(advance_by)

  def __eq__(self, other):
    return self.advance_by == other.advance_by

  def __hash__(self):
    return hash(self.advance_by)

  def __lt__(self, other):
    return self.advance_by < other.advance_by


class _WatermarkController(PTransform):
  def get_windowing(self, _):
    return core.Windowing(window.GlobalWindows())

  def expand(self, pcoll):
    return pvalue.PCollection.from_(pcoll)


class _TestStream(PTransform):
  # This tag is used on WatermarkEvents to control the watermark at the root
  # TestStream.
  WATERMARK_CONTROL_TAG = '_TestStream_Watermark'

  """Test stream that generates events on an unbounded PCollection of elements.

  Each event emits elements, advances the watermark or advances the processing
  time.  After all of the specified elements are emitted, ceases to produce
  output.
  """
  def __init__(self, output_tags, coder=coders.FastPrimitivesCoder, events=[],
               endpoint=''):
    assert coder is not None
    self.coder = coder
    self._events = self._add_watermark_advancements(output_tags, events)
    self._endpoint = endpoint
    self._is_done = False

  def _add_watermark_advancements(self, output_tags, events):
    """Adds watermark advancements to the given events.

    The following watermark advancements can be done on the runner side.
    However, it makes the logic on the runner side much more complicated than
    it needs to be.

    In order for watermarks to be properly advanced in a TestStream, a specific
    sequence of watermark holds must be sent:

    1. Hold the root watermark at -inf (this prevents the pipeline from
       immediately returning).
    2. Hold the watermarks at the WatermarkControllerss at -inf (this prevents
       the pipeline from immediately returning).
    3. Advance the root watermark to +inf - 1 (this allows the downstream
       WatermarkControllers to control their watermarks via holds).
    4. Advance watermarks as normal.
    5. Advance WatermarkController watermarks to +inf
    6. Advance root watermark to +inf.
    """
    if not events:
      return []

    watermark_starts = [WatermarkEvent(timestamp.MIN_TIMESTAMP, tag)
                        for tag in output_tags]
    watermark_stops = [WatermarkEvent(timestamp.MAX_TIMESTAMP, tag)
                       for tag in output_tags]

    test_stream_init = [WatermarkEvent(timestamp.MIN_TIMESTAMP,
                                       _TestStream.WATERMARK_CONTROL_TAG)]
    test_stream_start = [WatermarkEvent(
        timestamp.MAX_TIMESTAMP - timestamp.TIME_GRANULARITY,
        _TestStream.WATERMARK_CONTROL_TAG)]
    test_stream_stop = [WatermarkEvent(timestamp.MAX_TIMESTAMP,
                                        _TestStream.WATERMARK_CONTROL_TAG)]

    return (test_stream_init
            + watermark_starts
            + test_stream_start
            + events
            + watermark_stops
            + test_stream_stop)

  def get_windowing(self, unused_inputs):
    return core.Windowing(window.GlobalWindows())

  def expand(self, pcoll):
    return pvalue.PCollection(pcoll.pipeline, is_bounded=False)

  def _infer_output_coder(self, input_type=None, input_coder=None):
    return self.coder

  def _events_from_service(self):
    channel = grpc.insecure_channel(self._endpoint)
    stub = beam_interactive_api_pb2_grpc.InteractiveServiceStub(channel)
    request = beam_interactive_api_pb2.EventsRequest()
    for response in stub.Events(request):
      if response.end_of_stream:
        self._is_done = True
      else:
        self._is_done = False
      for event in response.events:
        if event.HasField('watermark_event'):
          yield WatermarkEvent(event.watermark_event.new_watermark)
        elif event.HasField('processing_time_event'):
          yield ProcessingTimeEvent(
              event.processing_time_event.advance_duration)
        elif event.HasField('element_event'):
          for element in event.element_event.elements:
            value = self.coder().decode(element.encoded_element)
            yield ElementEvent([TimestampedValue(value, element.timestamp)])

  def _events_from_script(self, index):
    if len(self._events) == 0:
      return
    yield self._events[index]

  def events(self, index):
    if self._endpoint:
      return self._events_from_service()
    return self._events_from_script(index)

  def begin(self):
    return 0

  def end(self, index):
    if self._endpoint:
      return self._is_done
    return index >= len(self._events)

  def next(self, index):
    if self._endpoint:
      return 0
    return index + 1


class _TestStreamMultiOutputs(object):
  """An object grouping the multiple outputs of a ParDo or FlatMap transform."""

  def __init__(self, pcoll_dict):
    self._pcolls = pcoll_dict

  def __str__(self):
    return '<%s>' % self._str_internal()

  def __repr__(self):
    return '<%s at %s>' % (self._str_internal(), hex(id(self)))

  def _str_internal(self):
    return '%s main_tag=%s tags=%s transform=%s' % (
        self.__class__.__name__, self._main_tag, self._tags, self._transform)

  def __iter__(self):
    """Iterates over tags returning for each call a (tag, pvalue) pair."""
    for tag in self._pcolls:
      yield self[tag]

  def __getattr__(self, tag):
    # Special methods which may be accessed before the object is
    # fully constructed (e.g. in unpickling).
    if tag[:2] == tag[-2:] == '__':
      return object.__getattr__(self, tag)
    return self[tag]

  def __getitem__(self, tag):
    # Accept int tags so that we can look at Partition tags with the
    # same ints that we used in the partition function.
    # TODO(gildea): Consider requiring string-based tags everywhere.
    # This will require a partition function that does not return ints.
    return self._pcolls[tag]


class TestStream(PTransform):
  """Test stream that generates events on an unbounded PCollection of elements.

  Each event emits elements, advances the watermark or advances the processing
  time.  After all of the specified elements are emitted, ceases to produce
  output.
  """
  def __init__(self, coder=coders.FastPrimitivesCoder, endpoint=''):
    assert coder is not None
    self.coder = coder
    self.watermarks = { None: timestamp.MIN_TIMESTAMP }
    self._events = []
    self._endpoint = endpoint
    self.output_tags = set()

  def get_windowing(self, unused_inputs):
    return core.Windowing(window.GlobalWindows())

  def expand(self, pbegin):
    assert(isinstance(pbegin, pvalue.PBegin))
    self.pipeline = pbegin.pipeline

    def mux(event):
      if event.tag:
        yield pvalue.TaggedOutput(event.tag, event)
      else:
        yield event

    mux_output = (pbegin
                  | _TestStream(self.output_tags, events=self._events)
                  | 'TestStream Multiplexer' >> beam.ParDo(mux).with_outputs())

    outputs = {}
    for tag in self.output_tags:
      label = '_WatermarkController[{}]'.format(tag)
      outputs[tag] = (mux_output[tag] | label >> _WatermarkController())

    if len(outputs) == 1:
      return list(outputs.values())[0]
    return _TestStreamMultiOutputs(outputs)

  def _infer_output_coder(self, input_type=None, input_coder=None):
    return self.coder

  def _add(self, event):
    if isinstance(event, ElementEvent):
      for tv in event.timestamped_values:
        assert tv.timestamp < timestamp.MAX_TIMESTAMP, (
            'Element timestamp must be before timestamp.MAX_TIMESTAMP.')
    elif isinstance(event, WatermarkEvent):
      if event.tag not in self.watermarks:
        self.watermarks[event.tag] = timestamp.MIN_TIMESTAMP
      assert event.new_watermark > self.watermarks[event.tag], (
          'Watermark must strictly-monotonically advance.')
      self.watermarks[event.tag] = event.new_watermark
    elif isinstance(event, ProcessingTimeEvent):
      assert event.advance_by > 0, (
          'Must advance processing time by positive amount.')
    else:
      raise ValueError('Unknown event: %s' % event)
    self._events.append(event)

  def add_elements(self, elements, tag=None, event_timestamp=None):
    """Add elements to the TestStream.

    Elements added to the TestStream will be produced during pipeline execution.
    These elements can be TimestampedValue, WindowedValue or raw unwrapped
    elements that are serializable using the TestStream's specified Coder.  When
    a TimestampedValue or a WindowedValue element is used, the timestamp of the
    TimestampedValue or WindowedValue will be the timestamp of the produced
    element; otherwise, the current watermark timestamp will be used for that
    element.  The windows of a given WindowedValue are ignored by the
    TestStream.
    """
    self.output_tags.add(tag)
    timestamped_values = []
    for element in elements:
      if isinstance(element, TimestampedValue):
        timestamped_values.append(element)
      elif isinstance(element, WindowedValue):
        # Drop windows for elements in test stream.
        timestamped_values.append(
            TimestampedValue(element.value, element.timestamp))
      else:
        # Add elements with timestamp equal to current watermark.
        timestamped_values.append(
            TimestampedValue(element, self.current_watermark))
    self._add(ElementEvent(timestamped_values))
    return self

  def advance_watermark_to(self, new_watermark):
    """Advance the watermark to a given Unix timestamp.

    The Unix timestamp value used must be later than the previous watermark
    value and should be given as an int, float or utils.timestamp.Timestamp
    object.
    """
    self.output_tags.add(tag)
    self._add(WatermarkEvent(new_watermark, tag))
    return self

  def advance_watermark_to_infinity(self):
    """Advance the watermark to the end of time, completing this TestStream."""
    self.advance_watermark_to(timestamp.MAX_TIMESTAMP)
    return self

  def advance_processing_time(self, advance_by):
    """Advance the current processing time by a given duration in seconds.

    The duration must be a positive second duration and should be given as an
    int, float or utils.timestamp.Duration object.
    """
    self._add(ProcessingTimeEvent(advance_by))
    return self
