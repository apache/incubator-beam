
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

"""Support for installing custom code and required dependencies.

Workflows, with the exception of very simple ones, are organized in multiple
modules and packages. Typically, these modules and packages have
dependencies on other standard libraries. Dataflow relies on the Python
setuptools package to handle these scenarios. For further details please read:
https://pythonhosted.org/an_example_pypi_project/setuptools.html

When a runner tries to run a pipeline it will check for a --requirements_file
and a --setup_file option.

If --setup_file is present then it is assumed that the folder containing the
file specified by the option has the typical layout required by setuptools and
it will run 'python setup.py sdist' to produce a source distribution. The
resulting tarball (a .tar or .tar.gz file) will be staged at the GCS staging
location specified as job option. When a worker starts it will check for the
presence of this file and will run 'easy_install tarball' to install the
package in the worker.

If --requirements_file is present then the file specified by the option will be
staged in the GCS staging location.  When a worker starts it will check for the
presence of this file and will run 'pip install -r requirements.txt'. A
requirements file can be easily generated by running 'pip freeze -r
requirements.txt'. The reason a Dataflow runner does not run this automatically
is because quite often only a small fraction of the dependencies present in a
requirements.txt file are actually needed for remote execution and therefore a
one-time manual trimming is desirable.

TODO(silviuc): Staged files should have a job specific prefix.
To prevent several jobs in the same project stomping on each other due to a
shared staging location.

TODO(silviuc): Should we allow several setup packages?
TODO(silviuc): We should allow customizing the exact command for setup build.
"""

import functools
import glob
import logging
import os
import re
import shutil
import sys
import tempfile

from apache_beam import version as beam_version
from apache_beam.internal import pickler
from apache_beam.io.filesystems import FileSystems
from apache_beam.options.pipeline_options import GoogleCloudOptions
from apache_beam.options.pipeline_options import SetupOptions
from apache_beam.runners.dataflow.internal import names
from apache_beam.utils import processes

# All constants are for internal use only; no backwards-compatibility
# guarantees.

# In a released version BEAM_CONTAINER_VERSION and BEAM_FNAPI_CONTAINER_VERSION
# should match each other, and should be in the same format as the SDK version
# (i.e. MAJOR.MINOR.PATCH). For non-released (dev) versions, read below.
# Update this version to the next version whenever there is a change that will
# require changes to legacy Dataflow worker execution environment.
# This should be in the beam-[version]-[date] format, date is optional.
BEAM_CONTAINER_VERSION = 'beam-2.2.0-20170807'
# Update this version to the next version whenever there is a change that
# requires changes to SDK harness container or SDK harness launcher.
# This should be in the beam-[version]-[date] format, date is optional.
BEAM_FNAPI_CONTAINER_VERSION = 'beam-2.1.0-20170621'

# Standard file names used for staging files.
WORKFLOW_TARBALL_FILE = 'workflow.tar.gz'
REQUIREMENTS_FILE = 'requirements.txt'
EXTRA_PACKAGES_FILE = 'extra_packages.txt'

# Package names for different distributions
GOOGLE_PACKAGE_NAME = 'google-cloud-dataflow'
BEAM_PACKAGE_NAME = 'apache-beam'

# SDK identifiers for different distributions
GOOGLE_SDK_NAME = 'Google Cloud Dataflow SDK for Python'
BEAM_SDK_NAME = 'Apache Beam SDK for Python'


def _dependency_file_copy(from_path, to_path):
  """Copies a local file to a GCS file or vice versa."""
  logging.info('file copy from %s to %s.', from_path, to_path)
  if from_path.startswith('gs://') or to_path.startswith('gs://'):
    from apache_beam.io.gcp import gcsio
    if from_path.startswith('gs://') and to_path.startswith('gs://'):
      # Both files are GCS files so copy.
      gcsio.GcsIO().copy(from_path, to_path)
    elif to_path.startswith('gs://'):
      # Only target is a GCS file, read local file and upload.
      with open(from_path, 'rb') as f:
        with gcsio.GcsIO().open(to_path, mode='wb') as g:
          pfun = functools.partial(f.read, gcsio.WRITE_CHUNK_SIZE)
          for chunk in iter(pfun, ''):
            g.write(chunk)
    else:
      # Source is a GCS file but target is local file.
      with gcsio.GcsIO().open(from_path, mode='rb') as g:
        with open(to_path, 'wb') as f:
          pfun = functools.partial(g.read, gcsio.DEFAULT_READ_BUFFER_SIZE)
          for chunk in iter(pfun, ''):
            f.write(chunk)
  else:
    # Branch used only for unit tests and integration tests.
    # In such environments GCS support is not available.
    if not os.path.isdir(os.path.dirname(to_path)):
      logging.info('Created folder (since we have not done yet, and any errors '
                   'will follow): %s ', os.path.dirname(to_path))
      os.mkdir(os.path.dirname(to_path))
    shutil.copyfile(from_path, to_path)


def _dependency_file_download(from_url, to_folder):
  """Downloads a file from a URL and returns path to the local file."""
  # TODO(silviuc): We should cache downloads so we do not do it for every job.
  try:
    # We check if the file is actually there because wget returns a file
    # even for a 404 response (file will contain the contents of the 404
    # response).
    response, content = __import__('httplib2').Http().request(from_url)
    if int(response['status']) >= 400:
      raise RuntimeError(
          'Beam SDK not found at %s (response: %s)' % (from_url, response))
    local_download_file = os.path.join(to_folder, 'beam-sdk.tar.gz')
    with open(local_download_file, 'w') as f:
      f.write(content)
  except Exception:
    logging.info('Failed to download Beam SDK from %s', from_url)
    raise
  return local_download_file


def _stage_extra_packages(extra_packages, staging_location, temp_dir,
                          file_copy=_dependency_file_copy):
  """Stages a list of local extra packages.

  Args:
    extra_packages: Ordered list of local paths to extra packages to be staged.
    staging_location: Staging location for the packages.
    temp_dir: Temporary folder where the resource building can happen. Caller
      is responsible for cleaning up this folder after this function returns.
    file_copy: Callable for copying files. The default version will copy from
      a local file to a GCS location using the gsutil tool available in the
      Google Cloud SDK package.

  Returns:
    A list of file names (no paths) for the resources staged. All the files
    are assumed to be staged in staging_location.

  Raises:
    RuntimeError: If files specified are not found or do not have expected
      name patterns.
  """
  resources = []
  staging_temp_dir = None
  local_packages = []
  for package in extra_packages:
    if not (os.path.basename(package).endswith('.tar') or
            os.path.basename(package).endswith('.tar.gz') or
            os.path.basename(package).endswith('.whl')):
      raise RuntimeError(
          'The --extra_package option expects a full path ending with '
          '".tar" or ".tar.gz" instead of %s' % package)
    if os.path.basename(package).endswith('.whl'):
      logging.warning(
          'The .whl package "%s" is provided in --extra_package. '
          'This functionality is not officially supported. Since wheel '
          'packages are binary distributions, this package must be '
          'binary-compatible with the worker environment (e.g. Python 2.7 '
          'running on an x64 Linux host).')

    if not os.path.isfile(package):
      if package.startswith('gs://'):
        if not staging_temp_dir:
          staging_temp_dir = tempfile.mkdtemp(dir=temp_dir)
        logging.info('Downloading extra package: %s locally before staging',
                     package)
        if os.path.isfile(staging_temp_dir):
          local_file_path = staging_temp_dir
        else:
          _, last_component = FileSystems.split(package)
          local_file_path = FileSystems.join(staging_temp_dir, last_component)
        _dependency_file_copy(package, local_file_path)
      else:
        raise RuntimeError(
            'The file %s cannot be found. It was specified in the '
            '--extra_packages command line option.' % package)
    else:
      local_packages.append(package)

  if staging_temp_dir:
    local_packages.extend(
        [FileSystems.join(staging_temp_dir, f) for f in os.listdir(
            staging_temp_dir)])

  for package in local_packages:
    basename = os.path.basename(package)
    staged_path = FileSystems.join(staging_location, basename)
    file_copy(package, staged_path)
    resources.append(basename)
  # Create a file containing the list of extra packages and stage it.
  # The file is important so that in the worker the packages are installed
  # exactly in the order specified. This approach will avoid extra PyPI
  # requests. For example if package A depends on package B and package A
  # is installed first then the installer will try to satisfy the
  # dependency on B by downloading the package from PyPI. If package B is
  # installed first this is avoided.
  with open(os.path.join(temp_dir, EXTRA_PACKAGES_FILE), 'wt') as f:
    for package in local_packages:
      f.write('%s\n' % os.path.basename(package))
  staged_path = FileSystems.join(staging_location, EXTRA_PACKAGES_FILE)
  # Note that the caller of this function is responsible for deleting the
  # temporary folder where all temp files are created, including this one.
  file_copy(os.path.join(temp_dir, EXTRA_PACKAGES_FILE), staged_path)
  resources.append(EXTRA_PACKAGES_FILE)

  return resources


def _get_python_executable():
  # Allow overriding the python executable to use for downloading and
  # installing dependencies, otherwise use the python executable for
  # the current process.
  python_bin = os.environ.get('BEAM_PYTHON') or sys.executable
  if not python_bin:
    raise ValueError('Could not find Python executable.')
  return python_bin


def _populate_requirements_cache(requirements_file, cache_dir):
  # The 'pip download' command will not download again if it finds the
  # tarball with the proper version already present.
  # It will get the packages downloaded in the order they are presented in
  # the requirements file and will not download package dependencies.
  cmd_args = [
      _get_python_executable(), '-m', 'pip', 'install', '--download', cache_dir,
      '-r', requirements_file,
      # Download from PyPI source distributions.
      '--no-binary', ':all:']
  logging.info('Executing command: %s', cmd_args)
  processes.check_call(cmd_args)


def stage_job_resources(
    options, file_copy=_dependency_file_copy, build_setup_args=None,
    temp_dir=None, populate_requirements_cache=_populate_requirements_cache):
  """For internal use only; no backwards-compatibility guarantees.

  Creates (if needed) and stages job resources to options.staging_location.

  Args:
    options: Command line options. More specifically the function will expect
      staging_location, requirements_file, setup_file, and save_main_session
      options to be present.
    file_copy: Callable for copying files. The default version will copy from
      a local file to a GCS location using the gsutil tool available in the
      Google Cloud SDK package.
    build_setup_args: A list of command line arguments used to build a setup
      package. Used only if options.setup_file is not None. Used only for
      testing.
    temp_dir: Temporary folder where the resource building can happen. If None
      then a unique temp directory will be created. Used only for testing.
    populate_requirements_cache: Callable for populating the requirements cache.
      Used only for testing.

  Returns:
    A list of file names (no paths) for the resources staged. All the files
    are assumed to be staged in options.staging_location.

  Raises:
    RuntimeError: If files specified are not found or error encountered while
      trying to create the resources (e.g., build a setup package).
  """
  temp_dir = temp_dir or tempfile.mkdtemp()
  resources = []

  google_cloud_options = options.view_as(GoogleCloudOptions)
  setup_options = options.view_as(SetupOptions)
  # Make sure that all required options are specified. There are a few that have
  # defaults to support local running scenarios.
  if google_cloud_options.staging_location is None:
    raise RuntimeError(
        'The --staging_location option must be specified.')
  if google_cloud_options.temp_location is None:
    raise RuntimeError(
        'The --temp_location option must be specified.')

  # Stage a requirements file if present.
  if setup_options.requirements_file is not None:
    if not os.path.isfile(setup_options.requirements_file):
      raise RuntimeError('The file %s cannot be found. It was specified in the '
                         '--requirements_file command line option.' %
                         setup_options.requirements_file)
    staged_path = FileSystems.join(google_cloud_options.staging_location,
                                   REQUIREMENTS_FILE)
    file_copy(setup_options.requirements_file, staged_path)
    resources.append(REQUIREMENTS_FILE)
    requirements_cache_path = (
        os.path.join(tempfile.gettempdir(), 'dataflow-requirements-cache')
        if setup_options.requirements_cache is None
        else setup_options.requirements_cache)
    # Populate cache with packages from requirements and stage the files
    # in the cache.
    if not os.path.exists(requirements_cache_path):
      os.makedirs(requirements_cache_path)
    populate_requirements_cache(
        setup_options.requirements_file, requirements_cache_path)
    for pkg in  glob.glob(os.path.join(requirements_cache_path, '*')):
      file_copy(pkg, FileSystems.join(google_cloud_options.staging_location,
                                      os.path.basename(pkg)))
      resources.append(os.path.basename(pkg))

  # Handle a setup file if present.
  # We will build the setup package locally and then copy it to the staging
  # location because the staging location is a GCS path and the file cannot be
  # created directly there.
  if setup_options.setup_file is not None:
    if not os.path.isfile(setup_options.setup_file):
      raise RuntimeError('The file %s cannot be found. It was specified in the '
                         '--setup_file command line option.' %
                         setup_options.setup_file)
    if os.path.basename(setup_options.setup_file) != 'setup.py':
      raise RuntimeError(
          'The --setup_file option expects the full path to a file named '
          'setup.py instead of %s' % setup_options.setup_file)
    tarball_file = _build_setup_package(setup_options.setup_file, temp_dir,
                                        build_setup_args)
    staged_path = FileSystems.join(google_cloud_options.staging_location,
                                   WORKFLOW_TARBALL_FILE)
    file_copy(tarball_file, staged_path)
    resources.append(WORKFLOW_TARBALL_FILE)

  # Handle extra local packages that should be staged.
  if setup_options.extra_packages is not None:
    resources.extend(
        _stage_extra_packages(setup_options.extra_packages,
                              google_cloud_options.staging_location,
                              temp_dir=temp_dir, file_copy=file_copy))

  # Pickle the main session if requested.
  # We will create the pickled main session locally and then copy it to the
  # staging location because the staging location is a GCS path and the file
  # cannot be created directly there.
  if setup_options.save_main_session:
    pickled_session_file = os.path.join(temp_dir,
                                        names.PICKLED_MAIN_SESSION_FILE)
    pickler.dump_session(pickled_session_file)
    staged_path = FileSystems.join(google_cloud_options.staging_location,
                                   names.PICKLED_MAIN_SESSION_FILE)
    file_copy(pickled_session_file, staged_path)
    resources.append(names.PICKLED_MAIN_SESSION_FILE)

  if hasattr(setup_options, 'sdk_location'):
    if setup_options.sdk_location == 'default':
      stage_tarball_from_remote_location = True
    elif (setup_options.sdk_location.startswith('gs://') or
          setup_options.sdk_location.startswith('http://') or
          setup_options.sdk_location.startswith('https://')):
      stage_tarball_from_remote_location = True
    else:
      stage_tarball_from_remote_location = False

    staged_path = FileSystems.join(google_cloud_options.staging_location,
                                   names.DATAFLOW_SDK_TARBALL_FILE)
    if stage_tarball_from_remote_location:
      # If --sdk_location is not specified then the appropriate package
      # will be obtained from PyPI (https://pypi.python.org) based on the
      # version of the currently running SDK. If the option is
      # present then no version matching is made and the exact URL or path
      # is expected.
      #
      # Unit tests running in the 'python setup.py test' context will
      # not have the sdk_location attribute present and therefore we
      # will not stage a tarball.
      if setup_options.sdk_location == 'default':
        sdk_remote_location = 'pypi'
      else:
        sdk_remote_location = setup_options.sdk_location
      _stage_beam_sdk_tarball(sdk_remote_location, staged_path, temp_dir)
      resources.append(names.DATAFLOW_SDK_TARBALL_FILE)
    else:
      # Check if we have a local Beam SDK tarball present. This branch is
      # used by tests running with the SDK built at head.
      if setup_options.sdk_location == 'default':
        module_path = os.path.abspath(__file__)
        sdk_path = os.path.join(
            os.path.dirname(module_path), '..', '..', '..',
            names.DATAFLOW_SDK_TARBALL_FILE)
      elif os.path.isdir(setup_options.sdk_location):
        sdk_path = os.path.join(
            setup_options.sdk_location, names.DATAFLOW_SDK_TARBALL_FILE)
      else:
        sdk_path = setup_options.sdk_location
      if os.path.isfile(sdk_path):
        logging.info('Copying Beam SDK "%s" to staging location.', sdk_path)
        file_copy(sdk_path, staged_path)
        resources.append(names.DATAFLOW_SDK_TARBALL_FILE)
      else:
        if setup_options.sdk_location == 'default':
          raise RuntimeError('Cannot find default Beam SDK tar file "%s"',
                             sdk_path)
        elif not setup_options.sdk_location:
          logging.info('Beam SDK will not be staged since --sdk_location '
                       'is empty.')
        else:
          raise RuntimeError(
              'The file "%s" cannot be found. Its location was specified by '
              'the --sdk_location command-line option.' %
              sdk_path)

  # Delete all temp files created while staging job resources.
  shutil.rmtree(temp_dir)
  return resources


def _build_setup_package(setup_file, temp_dir, build_setup_args=None):
  saved_current_directory = os.getcwd()
  try:
    os.chdir(os.path.dirname(setup_file))
    if build_setup_args is None:
      build_setup_args = [
          _get_python_executable(), os.path.basename(setup_file),
          'sdist', '--dist-dir', temp_dir]
    logging.info('Executing command: %s', build_setup_args)
    processes.check_call(build_setup_args)
    output_files = glob.glob(os.path.join(temp_dir, '*.tar.gz'))
    if not output_files:
      raise RuntimeError(
          'File %s not found.' % os.path.join(temp_dir, '*.tar.gz'))
    return output_files[0]
  finally:
    os.chdir(saved_current_directory)


def _stage_beam_sdk_tarball(sdk_remote_location, staged_path, temp_dir):
  """Stage a Beam SDK tarball with the appropriate version.

  Args:
    sdk_remote_location: A GCS path to a SDK tarball or a URL from
      the file can be downloaded.
    staged_path: GCS path where the found SDK tarball should be copied.
    temp_dir: path to temporary location where the file should be downloaded.

  Raises:
    RuntimeError: If wget on the URL specified returs errors or the file
      cannot be copied from/to GCS.
  """
  if (sdk_remote_location.startswith('http://') or
      sdk_remote_location.startswith('https://')):
    logging.info(
        'Staging Beam SDK tarball from %s to %s',
        sdk_remote_location, staged_path)
    local_download_file = _dependency_file_download(
        sdk_remote_location, temp_dir)
    _dependency_file_copy(local_download_file, staged_path)
  elif sdk_remote_location.startswith('gs://'):
    # Stage the file to the GCS staging area.
    logging.info(
        'Staging Beam SDK tarball from %s to %s',
        sdk_remote_location, staged_path)
    _dependency_file_copy(sdk_remote_location, staged_path)
  elif sdk_remote_location == 'pypi':
    logging.info('Staging the SDK tarball from PyPI to %s', staged_path)
    _dependency_file_copy(_download_pypi_sdk_package(temp_dir), staged_path)
  else:
    raise RuntimeError(
        'The --sdk_location option was used with an unsupported '
        'type of location: %s' % sdk_remote_location)


def get_default_container_image_for_current_sdk(job_type):
  """For internal use only; no backwards-compatibility guarantees.

  Args:
    job_type (str): BEAM job type.

  Returns:
    str: Google Cloud Dataflow container image for remote execution.
  """
  # TODO(tvalentyn): Use enumerated type instead of strings for job types.
  if job_type == 'FNAPI_BATCH' or job_type == 'FNAPI_STREAMING':
    image_name = 'dataflow.gcr.io/v1beta3/python-fnapi'
  else:
    image_name = 'dataflow.gcr.io/v1beta3/python'
  image_tag = _get_required_container_version(job_type)
  return image_name + ':' + image_tag


def _get_required_container_version(job_type=None):
  """For internal use only; no backwards-compatibility guarantees.

  Args:
    job_type (str, optional): BEAM job type. Defaults to None.

  Returns:
    str: The tag of worker container images in GCR that corresponds to
      current version of the SDK.
  """
  # TODO(silviuc): Handle apache-beam versions when we have official releases.
  import pkg_resources as pkg
  try:
    version = pkg.get_distribution(GOOGLE_PACKAGE_NAME).version
    # We drop any pre/post parts of the version and we keep only the X.Y.Z
    # format.  For instance the 0.3.0rc2 SDK version translates into 0.3.0.
    container_version = '%s.%s.%s' % pkg.parse_version(version)._version.release
    # We do, however, keep the ".dev" suffix if it is present.
    if re.match(r'.*\.dev[0-9]*$', version):
      container_version += '.dev'
    return container_version
  except pkg.DistributionNotFound:
    # This case covers Apache Beam end-to-end testing scenarios. All these tests
    # will run with a special container version.
    if job_type == 'FNAPI_BATCH' or job_type == 'FNAPI_STREAMING':
      return BEAM_FNAPI_CONTAINER_VERSION
    else:
      return BEAM_CONTAINER_VERSION


def get_sdk_name_and_version():
  """For internal use only; no backwards-compatibility guarantees.

  Returns name and version of SDK reported to Google Cloud Dataflow."""
  import pkg_resources as pkg
  container_version = _get_required_container_version()
  try:
    pkg.get_distribution(GOOGLE_PACKAGE_NAME)
    return (GOOGLE_SDK_NAME, container_version)
  except pkg.DistributionNotFound:
    return (BEAM_SDK_NAME, beam_version.__version__)


def get_sdk_package_name():
  """For internal use only; no backwards-compatibility guarantees.

  Returns the PyPI package name to be staged to Google Cloud Dataflow."""
  sdk_name, _ = get_sdk_name_and_version()
  if sdk_name == GOOGLE_SDK_NAME:
    return GOOGLE_PACKAGE_NAME
  else:
    return BEAM_PACKAGE_NAME


def _download_pypi_sdk_package(temp_dir):
  """Downloads SDK package from PyPI and returns path to local path."""
  package_name = get_sdk_package_name()
  import pkg_resources as pkg
  try:
    version = pkg.get_distribution(package_name).version
  except pkg.DistributionNotFound:
    raise RuntimeError('Please set --sdk_location command-line option '
                       'or install a valid {} distribution.'
                       .format(package_name))

  # Get a source distribution for the SDK package from PyPI.
  cmd_args = [
      _get_python_executable(), '-m', 'pip', 'install', '--download', temp_dir,
      '%s==%s' % (package_name, version),
      '--no-binary', ':all:', '--no-deps']
  logging.info('Executing command: %s', cmd_args)
  processes.check_call(cmd_args)
  zip_expected = os.path.join(
      temp_dir, '%s-%s.zip' % (package_name, version))
  if os.path.exists(zip_expected):
    return zip_expected
  tgz_expected = os.path.join(
      temp_dir, '%s-%s.tar.gz' % (package_name, version))
  if os.path.exists(tgz_expected):
    return tgz_expected
  raise RuntimeError(
      'Failed to download a source distribution for the running SDK. Expected '
      'either %s or %s to be found in the download folder.' % (
          zip_expected, tgz_expected))
