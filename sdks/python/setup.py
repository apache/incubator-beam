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

"""Apache Beam SDK setup configuration."""


import platform
import setuptools
import re


# Currently all compiled modules are optional (for performance only).
if platform.system() == 'Windows':
  # Windows doesn't always provide int64_t.
  cythonize = lambda *args, **kwargs: []
else:
  try:
      # pylint: disable=g-statement-before-imports,g-import-not-at-top
      from Cython.Build import cythonize
  except ImportError:
      cythonize = lambda *args, **kwargs: []


# Reads the actual version from pom.xml file,
def get_version_from_pom():
  with open('pom.xml', 'r') as f:
    pom = f.read()
    pom_version_regex = '.*<parent>\s*<groupId>[a-z\.]+</groupId>\s*<artifactId>[a-z\-]+</artifactId>\s*<version>([0-9a-zA-Z\.\-]+)</version>.*'
    pattern = re.compile(pom_version_regex)
    search = pattern.search(pom)
    return search.group(1).replace("-SNAPSHOT", ".dev")  # TODO: PEP 440 and incubating suffix


# Synchronizes apache_beam.__version__ field for later usage
def sync_version(version):
  init_path = 'apache_beam/__init__.py'
  regex = r'^__version__\s*=\s*".*"'
  with open(init_path, "r") as f:
    lines = f.readlines()
  with open(init_path, "w") as f:
    for line in lines:
      if re.search(regex, line):
        f.write(re.sub(regex, '__version__ = "%s"' % version, line))
      else:
        f.write(line)
  return version


version = get_version_from_pom()
sync_version(version)


# Configure the required packages and scripts to install.
REQUIRED_PACKAGES = [
    'avro>=1.7.7',
    'dill>=0.2.5',
    'google-apitools>=0.5.2',
    # TODO(silviuc): Reenable api client package dependencies when we can
    # update the packages to the latest version without affecting previous
    # SDK releases.
    # 'google-apitools-bigquery-v2',
    # 'google-apitools-dataflow-v1b3>=0.4.20160217',
    # 'google-apitools-storage-v1',
    'httplib2>=0.8',
    'mock>=1.0.1',
    'nose>=1.0',
    'oauth2client>=2.0.1',
    'protorpc>=0.9.1',
    'python-gflags>=2.0',
    'pyyaml>=3.10'
]

setuptools.setup(
    name='apache-beam-sdk',
    version=version,
    description='Apache Beam SDK for Python',
    long_description='',
    url='https://beam.incubator.apache.org',
    download_url='TBD',
    author='Apache Beam (incubating)',
    author_email='dev@beam.incubator.apache.org',
    packages=setuptools.find_packages(),
    ext_modules=cythonize([
        '**/*.pyx',
        'apache_beam/coders/coder_impl.py',
        'apache_beam/runners/common.py',
        'apache_beam/utils/counters.py',
    ]),
    install_requires=REQUIRED_PACKAGES,
    package_data={'apache_beam': ['*.pyx', '*.pxd', 'pom.xml']},
    test_suite='nose.collector',
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
    license='Apache License, 2.0',
    keywords='apache beam',
)
