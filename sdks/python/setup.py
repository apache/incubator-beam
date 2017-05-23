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

"""Apache Beam SDK for Python setup file."""

from distutils.version import StrictVersion

import glob
import os
import pkg_resources
import platform
import shutil
import subprocess
import sys
import warnings

import setuptools

from setuptools.command.build_py import build_py
from setuptools.command.sdist import sdist
from setuptools.command.test import test

from pkg_resources import get_distribution, DistributionNotFound


def get_version():
  global_names = {}
  exec(open(os.path.normpath('./apache_beam/version.py')).read(), global_names)
  return global_names['__version__']

PACKAGE_NAME = 'apache-beam'
PACKAGE_VERSION = get_version()
PACKAGE_DESCRIPTION = 'Apache Beam SDK for Python'
PACKAGE_URL = 'https://beam.apache.org'
PACKAGE_DOWNLOAD_URL = 'https://pypi.python.org/pypi/apache-beam'
PACKAGE_AUTHOR = 'Apache Software Foundation'
PACKAGE_EMAIL = 'dev@beam.apache.org'
PACKAGE_KEYWORDS = 'apache beam'
PACKAGE_LONG_DESCRIPTION = '''
Apache Beam is a unified programming model for both batch and streaming
data processing, enabling efficient execution across diverse distributed
execution engines and providing extensibility points for connecting to
different technologies and user communities.
'''

REQUIRED_PIP_VERSION = '7.0.0'
_PIP_VERSION = get_distribution('pip').version
if StrictVersion(_PIP_VERSION) < StrictVersion(REQUIRED_PIP_VERSION):
  warnings.warn(
      "You are using version {0} of pip. " \
      "However, version {1} is recommended.".format(
          _PIP_VERSION, REQUIRED_PIP_VERSION
      )
  )


REQUIRED_CYTHON_VERSION = '0.23.2'
try:
  _CYTHON_VERSION = get_distribution('cython').version
  if StrictVersion(_CYTHON_VERSION) < StrictVersion(REQUIRED_CYTHON_VERSION):
    warnings.warn(
        "You are using version {0} of cython. " \
        "However, version {1} is recommended.".format(
            _CYTHON_VERSION, REQUIRED_CYTHON_VERSION
        )
    )
except DistributionNotFound:
  # do nothing if Cython is not installed
  pass

# Currently all compiled modules are optional  (for performance only).
if platform.system() == 'Windows':
  # Windows doesn't always provide int64_t.
  cythonize = lambda *args, **kwargs: []
else:
  try:
    # pylint: disable=wrong-import-position
    from Cython.Build import cythonize
  except ImportError:
    cythonize = lambda *args, **kwargs: []


REQUIRED_PACKAGES = [
    'avro>=1.8.1,<2.0.0',
    'crcmod>=1.7,<2.0',
    'dill==0.2.6',
    'grpcio>=1.0,<2.0',
    'httplib2>=0.8,<0.10',
    'mock>=1.0.1,<3.0.0',
    'oauth2client>=2.0.1,<4.0.0',
    'protobuf==3.2.0',
    'pyyaml>=3.12,<4.0.0',
    ]

REQUIRED_SETUP_PACKAGES = [
    'nose>=1.0',
    ]

REQUIRED_TEST_PACKAGES = [
    'pyhamcrest>=1.9,<2.0',
    ]

GCP_REQUIREMENTS = [
  'google-apitools==0.5.10',
  'proto-google-cloud-datastore-v1==0.90.0',
  'googledatastore==7.0.1',
  # GCP packages required by tests
  'google-cloud-bigquery>=0.23.0,<0.24.0',
]


def generate_proto_files():
  py_sdk_root = os.path.dirname(os.path.abspath(__file__))
  common = os.path.join(os.path.dirname(py_sdk_root), 'common')
  proto_dirs = [os.path.join(common, '%s-api' % t, 'src', 'main', 'proto')
                for t in ('runner', 'fn')]
  proto_files = sum(
      [glob.glob(os.path.join(d, '*.proto')) for d in proto_dirs], [])
  out_dir = os.path.join(py_sdk_root, 'apache_beam', 'runners', 'api')
  out_files = [path for path in glob.glob(os.path.join(out_dir, '*_pb2.py'))]

  if not out_files and not proto_files:
    if not common:
      raise RuntimeError(
          'Not in apache git tree; unable to find proto definitions.')
    else:
      raise RuntimeError(
          'No proto files found at %s.' % proto_dirs)

  elif not out_files or len(out_files) < len(proto_files) or (
      proto_files
      and min(os.path.getmtime(path) for path in out_files)
      <= max(os.path.getmtime(path) for path in proto_files)):
    try:
      from grpc_tools import protoc
    except ImportError:
      raise RuntimeError(
          'grpcio-tools must be installed to generate proto stubs')

    print 'Regenerating out-of-date Python proto definitions.'
    proto_include = pkg_resources.resource_filename('grpc_tools', '_proto')
    args = (
      [sys.executable] +  # expecting to be called from command line
      ['--proto_path=%s' % proto_include] +
      ['--proto_path=%s' % d for d in proto_dirs] +
      ['--python_out=%s' % out_dir] +
      ['--grpc_python_out=%s' % out_dir] +
      proto_files)
    ret_code = protoc.main(args)
    if ret_code:
      raise RuntimeError(
          'Protoc returned non-zero status (see logs for details): '
          '%s' % ret_code)


# We must generate protos after setup_requires are installed.
def generate_protos_first(original_cmd):
  class cmd(original_cmd, object):
    def run(self):
      generate_proto_files()
      super(cmd, self).run()
  return cmd


# Though wheels are available for grpcio-tools, setup_requires uses
# easy_install which doesn't understand them.  This means that it is
# compiled from scratch (which is expensive as it compiles the full
# protoc compiler).  Instead, we attempt to install a wheel in a temporary
# directory and add it to the path.
# See https://github.com/pypa/setuptools/issues/377
try:
  import grpc_tools
except ImportError:
  grpcio_tools = 'grpcio-tools>=1.3.5'
  try:
    py_sdk_root = os.path.dirname(os.path.abspath(__file__))
    install_path = os.path.join(py_sdk_root, '.eggs', 'grpcio-virtualenv')
    warnings.warn(
        'Installing grpcio-tools is recommended for development; '
        'installing a local copy at %s' % install_path)
    subprocess.check_call(
        ['pip', 'install', '-t', install_path, '--upgrade', grpcio_tools])
    sys.path.append(install_path)
  except:
    REQUIRED_SETUP_PACKAGES.append(grpcio_tools)


setuptools.setup(
    name=PACKAGE_NAME,
    version=PACKAGE_VERSION,
    description=PACKAGE_DESCRIPTION,
    long_description=PACKAGE_LONG_DESCRIPTION,
    url=PACKAGE_URL,
    download_url=PACKAGE_DOWNLOAD_URL,
    author=PACKAGE_AUTHOR,
    author_email=PACKAGE_EMAIL,
    packages=setuptools.find_packages(),
    package_data={'apache_beam': [
        '*/*.pyx', '*/*/*.pyx', '*/*.pxd', '*/*/*.pxd', 'testing/data/*']},
    ext_modules=cythonize([
        'apache_beam/**/*.pyx',
        'apache_beam/coders/coder_impl.py',
        'apache_beam/metrics/execution.py',
        'apache_beam/runners/common.py',
        'apache_beam/runners/worker/logger.py',
        'apache_beam/runners/worker/opcounters.py',
        'apache_beam/runners/worker/operations.py',
        'apache_beam/transforms/cy_combiners.py',
        'apache_beam/utils/counters.py',
        'apache_beam/utils/windowed_value.py',
    ]),
    setup_requires=REQUIRED_SETUP_PACKAGES,
    install_requires=REQUIRED_PACKAGES,
    test_suite='nose.collector',
    tests_require=REQUIRED_TEST_PACKAGES,
    extras_require={
        'docs': ['Sphinx>=1.5.2,<2.0'],
        'test': REQUIRED_TEST_PACKAGES,
        'gcp': GCP_REQUIREMENTS
    },
    zip_safe=False,
    # PyPI package information.
    classifiers=[
        'Intended Audience :: End Users/Desktop',
        'License :: OSI Approved :: Apache Software License',
        'Operating System :: POSIX :: Linux',
        'Programming Language :: Python :: 2.7',
        'Topic :: Software Development :: Libraries',
        'Topic :: Software Development :: Libraries :: Python Modules',
        ],
    license='Apache License, Version 2.0',
    keywords=PACKAGE_KEYWORDS,
    entry_points={
        'nose.plugins.0.10': [
            'beam_test_plugin = test_config:BeamTestPlugin'
            ]},
    cmdclass={
        'build_py': generate_protos_first(build_py),
        'sdist': generate_protos_first(sdist),
        'test': generate_protos_first(test),
    },
)
