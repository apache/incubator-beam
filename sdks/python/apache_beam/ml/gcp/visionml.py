# pylint: skip-file
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

"""
A connector for sending API requests to the GCP Vision API.
"""

from __future__ import absolute_import

from typing import List
from typing import Optional
from typing import Tuple
from typing import Union

from future.utils import binary_type
from future.utils import text_type

from apache_beam import typehints
from apache_beam.metrics import Metrics
from apache_beam.transforms import DoFn
from apache_beam.transforms import FlatMap
from apache_beam.transforms import ParDo
from apache_beam.transforms import PTransform
from apache_beam.transforms import util
from cachetools.func import ttl_cache

try:
  from google.cloud import vision
except ImportError:
  raise ImportError(
      'Google Cloud Vision not supported for this execution environment '
      '(could not import google.cloud.vision).')

__all__ = [
    'AnnotateImage',
    'AnnotateImageWithContext',
    'BatchAnnotateImage',
    'BatchAnnotateImageWithContext'
]


@ttl_cache(maxsize=128, ttl=3600)
def get_vision_client(client_options=None):
  """Returns a Cloud Vision API client."""
  _client = vision.ImageAnnotatorClient(client_options=client_options)
  return _client


class AnnotateImage(PTransform):
  """A ``PTransform`` for annotating images using the GCP Vision API
  ref: https://cloud.google.com/vision/docs/

  Sends each element to the GCP Vision API. Element is a
  Union[text_type, binary_type] of either an URI (e.g. a GCS URI) or binary_type
  base64-encoded image data.
  Accepts an `AsDict` side input that maps each image to an image context.
  """
  def __init__(
      self,
      features,
      retry=None,
      timeout=120,
      client_options=None,
      context_side_input=None):
    """
    Args:
      features: (List[``vision.types.Feature.enums.Feature``]) Required.
        The Vision API features to detect
      retry: (google.api_core.retry.Retry) Optional.
        A retry object used to retry requests.
        If None is specified (default), requests will not be retried.
      timeout: (float) Optional.
        The time in seconds to wait for the response from the
        Vision API.
      client_options: (Union[dict, google.api_core.client_options.ClientOptions])
        Client options used to set user options on the client.
        API Endpoint should be set through client_options.
      context_side_input: (beam.pvalue.AsDict) Optional.
        An ``AsDict`` of a PCollection to be passed to the
        _ImageAnnotateFn as the image context mapping containing additional
        image context and/or feature-specific parameters.
        Example usage::

          image_contexts =
            [(''gs://cloud-samples-data/vision/ocr/sign.jpg'', Union[dict,
            ``vision.types.ImageContext()``]),
            (''gs://cloud-samples-data/vision/ocr/sign.jpg'', Union[dict,
            ``vision.types.ImageContext()``]),]

          context_side_input =
            (
              p
              | "Image contexts" >> beam.Create(image_contexts)
            )

          visionml.AnnotateImage(features,
            context_side_input=beam.pvalue.AsDict(context_side_input)))
    """
    super(AnnotateImage, self).__init__()
    self.features = features
    self.retry = retry
    self.timeout = timeout
    self.client_options = client_options
    self.context_side_input = context_side_input

  def expand(self, pvalue):
    return pvalue | ParDo(
        _ImageAnnotateFn(
            features=self.features,
            retry=self.retry,
            timeout=self.timeout,
            client_options=self.client_options),
        context_side_input=self.context_side_input)


@typehints.with_input_types(
    Union[text_type, binary_type], Optional[vision.types.ImageContext])
class _ImageAnnotateFn(DoFn):
  """A DoFn that sends each input element to the GCP Vision API
  service and outputs an element with the return result of the API
  (``google.cloud.vision_v1.types.AnnotateImageResponse``).
  """
  def __init__(self, features, retry, timeout, client_options):
    super(_ImageAnnotateFn, self).__init__()
    self._client = None
    self.features = features
    self.retry = retry
    self.timeout = timeout
    self.client_options = client_options
    self.counter = Metrics.counter(self.__class__, "API Calls")

  def setup(self):
    self._client = get_vision_client(self.client_options)

  def _annotate_image(self, element, image_context):
    if isinstance(element, text_type):
      image = vision.types.Image(
          source=vision.types.ImageSource(image_uri=element))
    else:  # Typehint checks only allows text_type or binary_type
      image = vision.types.Image(content=element)

    request = vision.types.AnnotateImageRequest(
        image=image, features=self.features, image_context=image_context)
    response = self._client.annotate_image(
        request=request, retry=self.retry, timeout=self.timeout)

    return response

  def process(self, element, context_side_input=None, *args, **kwargs):
    if context_side_input:  # If we have a side input image context, use that
      image_context = context_side_input.get(element)
    else:
      image_context = None
    response = self._annotate_image(element, image_context)
    self.counter.inc()
    yield response


class AnnotateImageWithContext(AnnotateImage):
  """A ``PTransform`` for annotating images using the GCP Vision API
  ref: https://cloud.google.com/vision/docs/

  Sends each element to the GCP Vision API. Element is a tuple of

      (Union[text_type, binary_type],
      Optional[``vision.types.ImageContext``])

  where the former is either an URI (e.g. a GCS URI) or binary_type
  base64-encoded image data.
  """
  def __init__(self, features, retry=None, timeout=120, client_options=None):
    """
     Args:
      features: (List[``vision.types.Feature.enums.Feature``]) Required.
        The Vision API features to detect
      retry: (google.api_core.retry.Retry) Optional.
        A retry object used to retry requests.
        If None is specified (default), requests will not be retried.
      timeout: (float) Optional.
        The time in seconds to wait for the response from the
        Vision API
      client_options: (Union[dict, google.api_core.client_options.ClientOptions])
        Client options used to set user options on the client.
        API Endpoint should be set through client_options.
    """
    super(AnnotateImageWithContext, self).__init__(
        features=features,
        retry=retry,
        timeout=timeout,
        client_options=client_options)

  def expand(self, pvalue):
    return pvalue | ParDo(
        _ImageAnnotateFnWithContext(
            features=self.features,
            retry=self.retry,
            timeout=self.timeout,
            client_options=self.client_options))


@typehints.with_input_types(
    Tuple[Union[text_type, binary_type], Optional[vision.types.ImageContext]])
class _ImageAnnotateFnWithContext(_ImageAnnotateFn):
  """A DoFn that unpacks each input tuple to element, image_context variables
  and sends these to the GCP Vision API service and outputs an element with the
  return result of the API
  (``google.cloud.vision_v1.types.AnnotateImageResponse``).
  """
  def __init__(self, features, retry, timeout, client_options):
    super(_ImageAnnotateFnWithContext, self).__init__(
        features=features,
        retry=retry,
        timeout=timeout,
        client_options=client_options)

  def process(self, element, *args, **kwargs):
    element, image_context = element  # Unpack (image, image_context) tuple
    response = self._annotate_image(element, image_context)
    self.counter.inc()
    yield response


class BatchAnnotateImage(PTransform):
  """A ``PTransform`` for batch (offline) annotating images using the
  GCP Vision API. ref: https://cloud.google.com/vision/docs/

  Sends each batch of elements to the GCP Vision API.
  Element is a Union[text_type, binary_type] of either an URI (e.g. a GCS URI)
  or binary_type base64-encoded image data.
  Accepts an `AsDict` side input that maps each image to an image context.
  """

  MAX_BATCH_SIZE = 5

  def __init__(
      self,
      features,
      retry=None,
      timeout=120,
      max_batch_size=None,
      min_batch_size=1,
      client_options=None,
      context_side_input=None,
      metadata=None):
    """
    Args:
      features: (List[``vision.types.Feature.enums.Feature``]) Required.
        The Vision API features to detect
      retry: (google.api_core.retry.Retry) Optional.
        A retry object used to retry requests.
        If None is specified (default), requests will not be retried.
      timeout: (float) Optional.
        The time in seconds to wait for the response from the Vision API.
        Default is 120 for single-element requests and 300 for batch annotation.
      max_batch_size: (int) Maximum number of images to batch in the same
        request to the Vision API.
        Default is 5 (which is also the Vision API max).
      min_batch_size: (int) Minimum number of images to batch in the same
        request to the Vision API. Default is 1.
      client_options: (Union[dict, google.api_core.client_options.ClientOptions])
        Client options used to set user options on the client.
        API Endpoint should be set through client_options.
      context_side_input: (beam.pvalue.AsDict) Optional.
        An ``AsDict`` of a PCollection to be passed to the
        _ImageAnnotateFn as the image context mapping containing additional
        image context and/or feature-specific parameters.
        Example usage::

          image_contexts =
            [(''gs://cloud-samples-data/vision/ocr/sign.jpg'', Union[dict,
            ``vision.types.ImageContext()``]),
            (''gs://cloud-samples-data/vision/ocr/sign.jpg'', Union[dict,
            ``vision.types.ImageContext()``]),]

          context_side_input =
            (
              p
              | "Image contexts" >> beam.Create(image_contexts)
            )

          visionml.AsyncBatchAnnotateImage(features, output_config,
            context_side_input=beam.pvalue.AsDict(context_side_input)))
      metadata: (Optional[Sequence[Tuple[str, str]]]): Optional.
        Additional metadata that is provided to the method.
    """
    super(BatchAnnotateImage, self).__init__()
    self.features = features
    self.retry = retry
    self.timeout = timeout
    self.max_batch_size = max_batch_size or BatchAnnotateImage.MAX_BATCH_SIZE
    if self.max_batch_size > BatchAnnotateImage.MAX_BATCH_SIZE:
      raise ValueError(
          'Max batch_size exceeded. '
          'Batch size needs to be smaller than {}'.format(
              BatchAnnotateImage.MAX_BATCH_SIZE))
    self.min_batch_size = min_batch_size
    self.client_options = client_options
    self.context_side_input = context_side_input
    self.metadata = metadata

  def expand(self, pvalue):
    return (
        pvalue
        | FlatMap(self._create_image_annotation_pairs, self.context_side_input)
        | util.BatchElements(
            min_batch_size=self.min_batch_size,
            max_batch_size=self.max_batch_size)
        | ParDo(
            _BatchImageAnnotateFn(
                features=self.features,
                retry=self.retry,
                timeout=self.timeout,
                client_options=self.client_options,
                metadata=self.metadata)))

  @typehints.with_input_types(
      Union[text_type, binary_type], Optional[vision.types.ImageContext])
  @typehints.with_output_types(List[vision.types.AnnotateImageRequest])
  def _create_image_annotation_pairs(self, element, context_side_input):
    if context_side_input:  # If we have a side input image context, use that
      image_context = context_side_input.get(element)
    else:
      image_context = None

    if isinstance(element, text_type):
      image = vision.types.Image(
          source=vision.types.ImageSource(image_uri=element))
    else:  # Typehint checks only allows text_type or binary_type
      image = vision.types.Image(content=element)

    request = vision.types.AnnotateImageRequest(
        image=image, features=self.features, image_context=image_context)
    yield request


class BatchAnnotateImageWithContext(BatchAnnotateImage):
  """A ``PTransform`` for batch (offline) annotating images using the
  GCP Vision API. ref: https://cloud.google.com/vision/docs/batch

  Sends each element to the GCP Vision API. Element is a tuple of

    (Union[text_type, binary_type],
    Optional[``vision.types.ImageContext``])

  where the former is either an URI (e.g. a GCS URI) or binary_type
  base64-encoded image data.
  """
  def __init__(
      self,
      features,
      retry=None,
      timeout=120,
      max_batch_size=None,
      min_batch_size=1,
      client_options=None,
      metadata=None):
    """
    Args:
      features: (List[``vision.types.Feature.enums.Feature``]) Required.
        The Vision API features to detect
      retry: (google.api_core.retry.Retry) Optional.
        A retry object used to retry requests.
        If None is specified (default), requests will not be retried.
      timeout: (float) Optional.
        The time in seconds to wait for the response from the Vision API.
        Default is 120 for single-element requests and 300 for batch annotation.
      max_batch_size: (int) Maximum number of images to batch in the same
        request to the Vision API.
        Default is 5 (which is also the Vision API max).
      min_batch_size: (int) Minimum number of images to batch in the same
        request to the Vision API. Default is 1.
      client_options: (Union[dict, google.api_core.client_options.ClientOptions])
        Client options used to set user options on the client.
        API Endpoint should be set through client_options.
      metadata: (Optional[Sequence[Tuple[str, str]]]): Optional.
        Additional metadata that is provided to the method.
    """
    super(BatchAnnotateImageWithContext, self).__init__(
        features=features,
        retry=retry,
        timeout=timeout,
        max_batch_size=max_batch_size,
        min_batch_size=min_batch_size,
        client_options=client_options,
        metadata=metadata)

  def expand(self, pvalue):
    return (
        pvalue
        | FlatMap(self._create_image_annotation_pairs)
        | util.BatchElements(
            min_batch_size=self.min_batch_size,
            max_batch_size=self.max_batch_size)
        | ParDo(
            _BatchImageAnnotateFn(
                features=self.features,
                retry=self.retry,
                timeout=self.timeout,
                client_options=self.client_options,
                metadata=self.metadata)))

  @typehints.with_input_types(
      Tuple[Union[text_type, binary_type], Optional[vision.types.ImageContext]])
  @typehints.with_output_types(List[vision.types.AnnotateImageRequest])
  def _create_image_annotation_pairs(self, element, **kwargs):
    element, image_context = element  # Unpack (image, image_context) tuple
    if isinstance(element, text_type):
      image = vision.types.Image(
          source=vision.types.ImageSource(image_uri=element))
    else:  # Typehint checks only allows text_type or binary_type
      image = vision.types.Image(content=element)

    request = vision.types.AnnotateImageRequest(
        image=image, features=self.features, image_context=image_context)
    yield request


@typehints.with_input_types(List[vision.types.AnnotateImageRequest])
class _BatchImageAnnotateFn(DoFn):
  """A DoFn that sends each input element to the GCP Vision API
  service in batches.
  Returns a ``google.cloud.vision.types.AsyncBatchAnnotateImagesResponse``.
  """
  def __init__(self, features, retry, timeout, client_options, metadata):
    super(_BatchImageAnnotateFn, self).__init__()
    self._client = None
    self.features = features
    self.retry = retry
    self.timeout = timeout
    self.client_options = client_options
    self.metadata = metadata
    self.counter = Metrics.counter(self.__class__, "API Calls")

  def start_bundle(self):
    self._client = get_vision_client(self.client_options)

  def process(self, element, *args, **kwargs):
    response = self._client.batch_annotate_images(
        requests=element,
        retry=self.retry,
        timeout=self.timeout,
        metadata=self.metadata)
    self.counter.inc()
    yield response
