// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package passert

import (
	"fmt"
	"reflect"
	"strings"

	"github.com/apache/beam/sdks/go/pkg/beam"
	"github.com/apache/beam/sdks/go/pkg/beam/core/util/reflectx"
	"github.com/apache/beam/sdks/go/pkg/beam/internal/errors"
)

// AllWithinBounds checks that a PCollection of numeric types is within the bounds
// [lo, high]. Checks for case where bounds are flipped and swaps them so the bounds
// passed to the doFn are always lo <= hi.
func AllWithinBounds(s beam.Scope, col beam.PCollection, lo, hi float64) {
	t := beam.ValidateNonCompositeType(col)
	if !reflectx.IsNumber(t.Type()) || reflectx.IsComplex(t.Type()) {
		panic(fmt.Sprintf("type must be a non-complex number: %v", t))
	}
	if lo > hi {
		lo, hi = hi, lo
	}
	s = s.Scope(fmt.Sprintf("passert.AllWithinBounds([%v, %v])", lo, hi))
	beam.ParDo0(s, &boundsFn{lo: lo, hi: hi}, beam.Impulse(s), beam.SideInput{Input: col})
}

type boundsFn struct {
	lo, hi float64
}

func (f *boundsFn) ProcessElement(_ []byte, col func(*beam.T) bool) error {
	errorStrings := []string{}
	var input beam.T
	for col(&input) {
		val := reflect.ValueOf(input.(interface{})).Convert(reflectx.Float64).Interface().(float64)
		if val < f.lo {
			errorStrings = append(errorStrings, fmt.Sprintf("value %v too low", input))
		} else if val > f.hi {
			errorStrings = append(errorStrings, fmt.Sprintf("value %v too high", input))
		}
	}
	if len(errorStrings) != 0 {
		errorStrings = append([]string{fmt.Sprintf("passert.AllWithinBounds([%v, %v]) failed", f.lo, f.hi)}, errorStrings...)
		return errors.New(strings.Join(errorStrings, "\n"))
	}
	return nil
}
