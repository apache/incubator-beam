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

package beam

import (
	"fmt"

	"github.com/apache/beam/sdks/go/pkg/beam/core/runtime/graphx"

	"github.com/apache/beam/sdks/go/pkg/beam/core/graph"
	"github.com/apache/beam/sdks/go/pkg/beam/internal/errors"
	pipepb "github.com/apache/beam/sdks/go/pkg/beam/model/pipeline_v1"
)

// ExternalTransform ...
type ExternalTransform struct {
	id                int
	urn               string
	payload           []byte
	in                []PCollection
	out               []FullType
	bounded           bool
	expansionAddr     string
	components        *pipepb.Components
	expandedTransform *pipepb.PTransform
}

// func (s ExternalTransform) withExpansionAddress(addr string) ExternalTransform {
// 	return
// }

// func CrossLanguage(e ExternalTransform) []PCollection {
func CrossLanguage(s Scope, e ExternalTransform) {

	// id, bounded, components and expandedTransform should be absent

	if e.expansionAddr == "" { // What's the best way to check if the value was ever set
		// return Legacy External API
	}

	// Add ExternalTranform to the Graph
	/*
		// Inserting a further subscope for better observability
		s := s.Scope(expansionAddr)
	*/
	if !s.IsValid() {
		// return nil, errors.New("invalid scope")
		fmt.Println("invalid scope")
	}
	for i, col := range e.in {
		if !col.IsValid() {
			// return nil, errors.Errorf("invalid pcollection to external: index %v", i)
			fmt.Printf("\ninvalid pcollection to external: index %v", i)

		}
	}

	var ins []*graph.Node
	for _, col := range e.in {
		ins = append(ins, col.n)
	}
	edge := graph.NewCrossLanguage(s.real, s.scope, ins)
	m, err := graphx.Marshal([]*graph.MultiEdge{edge}, &graphx.Options{})
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println(m)
	// Build ExpansionRequest
	// Correct way to serialize the input keys
	/*
		externalTransform := &pipepb.PTransform{}
		expansionReq := &jobpb.ExpansionRequest{
			Components: {},
			Transform:  {},
			Namespace:  "",
		}
	*/
	// Query Expansion Service
	//	 Manage GRPC Client

	// Handling ExpansionResponse
	if e.out == nil {
		// Infer output types from ExpansionResponse and update e.out
	}

	// for len(out) create output nodes
}

// External defines a Beam external transform. The interpretation of this primitive is runner
// specific. The runner is responsible for parsing the payload based on the
// spec provided to implement the behavior of the operation. Transform
// libraries should expose an API that captures the user's intent and serialize
// the payload as a byte slice that the runner will deserialize.
func External(s Scope, spec string, payload []byte, in []PCollection, out []FullType, bounded bool) []PCollection {
	return MustN(TryExternal(s, spec, payload, in, out, bounded))
}

// TryExternal attempts to perform the work of External, returning an error indicating why the operation
// failed.
func TryExternal(s Scope, spec string, payload []byte, in []PCollection, out []FullType, bounded bool) ([]PCollection, error) {
	if !s.IsValid() {
		return nil, errors.New("invalid scope")
	}
	for i, col := range in {
		if !col.IsValid() {
			return nil, errors.Errorf("invalid pcollection to external: index %v", i)
		}
	}

	var ins []*graph.Node
	for _, col := range in {
		ins = append(ins, col.n)
	}
	edge := graph.NewExternal(s.real, s.scope, &graph.Payload{URN: spec, Data: payload}, ins, out, bounded)

	var ret []PCollection
	for _, out := range edge.Output {
		c := PCollection{out.To}
		c.SetCoder(NewCoder(c.Type()))
		ret = append(ret, c)
	}
	return ret, nil
}
