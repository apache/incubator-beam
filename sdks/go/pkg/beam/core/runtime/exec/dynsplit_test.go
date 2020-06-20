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

package exec

import (
	"context"
	"io"
	"reflect"
	"sync"
	"testing"

	"github.com/apache/beam/sdks/go/pkg/beam/core/graph"
	"github.com/apache/beam/sdks/go/pkg/beam/core/graph/coder"
	"github.com/apache/beam/sdks/go/pkg/beam/core/graph/mtime"
	"github.com/apache/beam/sdks/go/pkg/beam/core/graph/window"
	"github.com/apache/beam/sdks/go/pkg/beam/core/typex"
	"github.com/apache/beam/sdks/go/pkg/beam/core/util/reflectx"
	"github.com/apache/beam/sdks/go/pkg/beam/internal/errors"
	"github.com/apache/beam/sdks/go/pkg/beam/io/rtrackers/offsetrange"
	"github.com/google/go-cmp/cmp"
)

// TestDynamicSplit tests that a dynamic split of an in-progress SDF succeeds
// with valid input. It coordinates the two threads (processing and splitting)
// to test what happens if operations happen in various orders. The test then
// validates that the output of the SDF is correct according to the split.
func TestDynamicSplit(t *testing.T) {
	tests := []struct {
		name string
		// driver is a function determining how the processing and splitting
		// threads are created and coordinated.
		driver func(*Plan, DataContext, *splitTestSdf) (error, splitResult)
	}{
		{
			// Complete a split before beginning processing.
			name:   "Simple",
			driver: nonBlockingDriver,
		},
		{
			// Try claiming while blocked on a split.
			name:   "BlockOnSplit",
			driver: splitBlockingDriver,
		},
		{
			// Try splitting while blocked on a claim.
			name:   "BlockOnClaim",
			driver: claimBlockingDriver,
		},
	}
	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			// Create pipeline.
			sdf := newSplitTestSdf()
			dfn, err := graph.NewDoFn(sdf, graph.NumMainInputs(graph.MainSingle))
			if err != nil {
				t.Fatalf("invalid function: %v", err)
			}
			cdr := createSplitTestInCoder()
			plan, out := createSdfPlan(t, t.Name(), dfn, cdr)

			// Create thread to send element to pipeline.
			pr, pw := io.Pipe()
			go writeElm(createElm(), cdr, pw)
			dc := DataContext{Data: &TestDataManager{R: pr}}

			// Call driver to coordinate processing & splitting threads.
			procRes, splitRes := test.driver(plan, dc, sdf)

			// Validate we get a valid split result (exact values don't matter).
			// TODO(BEAM-10289): Check Plan.Split results once dynamic splitting is implemented.
			if splitRes.err != nil {
				t.Fatalf("Plan.Split failed: %v", splitRes.err)
			}

			// Validate SDF output. Make sure each restriction matches the split result.
			if err := procRes; err != nil {
				t.Fatal(err)
			}
			if got, want := len(out.Elements), int(splitRes.primary.End-splitRes.primary.Start); got != want {
				t.Errorf("Unexpected number of elements: got: %v, want: %v", got, want)
			}
			for i, fv := range out.Elements {
				rest := fv.Elm.(offsetrange.Restriction)
				if got, want := rest, splitRes.primary; !cmp.Equal(got, want) {
					t.Errorf("Output element %v had incorrect restriction: got: %v, want: %v", i, got, want)
				}
			}
		})
	}
}

// nonBlockingDriver performs a split before starting processing, so no thread
// is forced to wait on a mutex.
func nonBlockingDriver(plan *Plan, dc DataContext, sdf *splitTestSdf) (procRes error, splitRes splitResult) {
	// Begin processing pipeline.
	procResCh := make(chan error)
	go processPlan(plan, dc, procResCh)
	rt := <-sdf.rt // Tracker is created first, retrieve that.

	// Complete a split before unblocking processing.
	splitResCh := make(chan splitResult)
	go splitPlan(rt, splitResCh)
	<-rt.split
	<-rt.blockSplit
	splitRes = <-splitResCh

	// Unblock and finishing processing.
	<-sdf.proc
	<-rt.claim
	<-rt.blockClaim
	<-rt.endClaim
	procRes = <-procResCh

	return procRes, splitRes
}

// splitBlockingDriver blocks on a split request so that the SDF attempts to
// claim while the split is occurring.
func splitBlockingDriver(plan *Plan, dc DataContext, sdf *splitTestSdf) (procRes error, splitRes splitResult) {
	// Begin processing pipeline.
	procResCh := make(chan error)
	go processPlan(plan, dc, procResCh)
	rt := <-sdf.rt // Tracker is created first, retrieve that.

	// Start a split, but block on it so it holds the mutex.
	splitResCh := make(chan splitResult)
	go splitPlan(rt, splitResCh)
	<-rt.split

	// Start processing and start a claim, that'll be waiting for the mutex.
	<-sdf.proc
	<-rt.claim

	// Unblock and finish splitting and free the mutex.
	<-rt.blockSplit
	splitRes = <-splitResCh

	// Unblock and finish claiming and processing.
	<-rt.blockClaim
	<-rt.endClaim
	procRes = <-procResCh

	return procRes, splitRes
}

// claimBlockingDriver blocks on a claim request so that the SDF attempts to
// split while the claim is occurring.
func claimBlockingDriver(plan *Plan, dc DataContext, sdf *splitTestSdf) (procRes error, splitRes splitResult) {
	// Begin processing pipeline.
	procResCh := make(chan error)
	go processPlan(plan, dc, procResCh)
	rt := <-sdf.rt // Tracker is created first, retrieve that.

	// Start a claim, but block on it so it holds the mutex.
	<-sdf.proc
	<-rt.claim

	// Start a split that'll be waiting for the mutex.
	splitResCh := make(chan splitResult)
	go splitPlan(rt, splitResCh)
	<-rt.split

	// Unblock the claim, freeing the mutex (but not finishing processing yet).
	<-rt.blockClaim

	// Finish splitting, allowing processing to finish.
	<-rt.blockSplit
	splitRes = <-splitResCh
	<-rt.endClaim // Delay the claim end so we don't process too much before splitting.
	procRes = <-procResCh

	return procRes, splitRes
}

// createElm creates the element for our test pipeline.
func createElm() *FullValue {
	return &FullValue{
		Elm: &FullValue{
			Elm:  20,
			Elm2: offsetrange.Restriction{Start: 0, End: 20},
		},
		Elm2: float64(20),
	}
}

// createSplitTestInCoder outputs the coder for inputs to our test pipeline,
// (in particular, the DataSource transform of the pipeline). For the specific
// element this is a coder for, see createElm.
func createSplitTestInCoder() *coder.Coder {
	restT := reflect.TypeOf((*offsetrange.Restriction)(nil)).Elem()
	restCdr := coder.LookupCustomCoder(restT)

	cdr := coder.NewW(
		coder.NewKV([]*coder.Coder{
			coder.NewKV([]*coder.Coder{
				intCoder(reflectx.Int),
				{Kind: coder.Custom, T: typex.New(restT), Custom: restCdr},
			}),
			coder.NewDouble(),
		}),
		coder.NewGlobalWindow())
	return cdr
}

// createSdfPlan creates a plan containing the test pipeline, which is
// DataSource -> SDF.ProcessSizedElementsAndRestrictions -> CaptureNode.
func createSdfPlan(t *testing.T, name string, fn *graph.DoFn, cdr *coder.Coder) (*Plan, *CaptureNode) {
	out := &CaptureNode{UID: 0}
	n := &ParDo{UID: 1, Fn: fn, Out: []Node{out}}
	sdf := &ProcessSizedElementsAndRestrictions{PDo: n}
	ds := &DataSource{
		UID:   2,
		SID:   StreamID{PtransformID: "DataSource"},
		Name:  "name",
		Coder: cdr,
		Out:   sdf,
	}
	units := []Unit{ds, sdf, out}

	p, err := NewPlan(name+"_plan", units)
	if err != nil {
		t.Fatalf("NewPlan failed: %v", err)
	}
	return p, out
}

// writeElm is meant to be the goroutine for feeding an element to the
// DataSourc of the test pipeline.
func writeElm(elm *FullValue, cdr *coder.Coder, pw *io.PipeWriter) {
	wc := MakeWindowEncoder(cdr.Window)
	ec := MakeElementEncoder(coder.SkipW(cdr))
	if err := EncodeWindowedValueHeader(wc, window.SingleGlobalWindow, mtime.ZeroTimestamp, pw); err != nil {
		panic("err")
	}
	if err := ec.Encode(elm, pw); err != nil {
		panic("err")
	}
	if err := pw.Close(); err != nil {
		panic("err")
	}
}

// processPlan is meant to be the goroutine representing the thread processing
// the SDF.
func processPlan(plan *Plan, dc DataContext, result chan error) {
	if err := plan.Execute(context.Background(), plan.ID()+"_execute", dc); err != nil {
		result <- errors.Wrap(err, "Plan.Execute failed")
	}
	if err := plan.Down(context.Background()); err != nil {
		result <- errors.Wrap(err, "Plan.Down failed")
	}
	result <- nil
}

type splitResult struct {
	primary, residual offsetrange.Restriction
	err               error
}

// splitPlan is meant to be the goroutine representing the thread handling a
// split request for the SDF.
func splitPlan(rt *splitTestRTracker, result chan splitResult) {
	// Fake splitting code.
	p, r, err := rt.TrySplit(0.5)
	result <- splitResult{
		primary:  p.(offsetrange.Restriction),
		residual: r.(offsetrange.Restriction),
		err:      err,
	}

	// TODO(BEAM-10289): Use this code when dynamic splitting is implemented.
	//	split, err := plan.Split(SplitPoints{Frac: 0.5, BufSize: 1})
	//	result <- splitResult{split: split, err: err}
}

// splitTestRTracker adds signals needed to coordinate splitting and claiming
// over multiple threads for this test. Semantically, this tracker is an
// offset range tracker representing a range of integers to output.
type splitTestRTracker struct {
	mu sync.Mutex // Lock on accessing underlying tracker.
	rt *offsetrange.Tracker

	// Send signals when starting a claim, blocking a claim, and ending a claim.
	claim      chan struct{}
	blockClaim chan struct{}
	endClaim   chan struct{}
	blockInd   int64 // Only send signals when claiming a specific position.

	// Send signals when starting a split, and blocking a split.
	split      chan struct{}
	blockSplit chan struct{}
}

func newSplitTestRTracker(rest offsetrange.Restriction) *splitTestRTracker {
	return &splitTestRTracker{
		rt:         offsetrange.NewTracker(rest),
		claim:      make(chan struct{}, 1),
		blockClaim: make(chan struct{}),
		endClaim:   make(chan struct{}),
		blockInd:   rest.Start,
		split:      make(chan struct{}, 1),
		blockSplit: make(chan struct{}),
	}
}

func (rt *splitTestRTracker) TryClaim(pos interface{}) bool {
	i := pos.(int64)
	if i == rt.blockInd {
		rt.claim <- struct{}{}
	}

	rt.mu.Lock()
	if i == rt.blockInd {
		rt.blockClaim <- struct{}{}
	}
	result := rt.rt.TryClaim(pos)
	rt.mu.Unlock()

	if i == rt.blockInd {
		rt.endClaim <- struct{}{}
	}
	return result
}

func (rt *splitTestRTracker) GetError() error {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	return rt.rt.GetError()
}

func (rt *splitTestRTracker) TrySplit(fraction float64) (interface{}, interface{}, error) {
	rt.split <- struct{}{}

	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.blockSplit <- struct{}{}
	return rt.rt.TrySplit(fraction)
}

func (rt *splitTestRTracker) GetProgress() (float64, float64) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	return rt.rt.GetProgress()
}

func (rt *splitTestRTracker) IsDone() bool {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	return rt.rt.IsDone()
}

func (rt *splitTestRTracker) GetRestriction() offsetrange.Restriction {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	return rt.rt.Rest
}

// splitTestSdf has signals needed to control processing behavior over multiple
// threads. The actual behavior is to accept an integer N as the element and
// output each element in the range of [0, N).
type splitTestSdf struct {
	proc chan struct{}
	rt   chan *splitTestRTracker // Used to provide created trackers to the test code.
}

func newSplitTestSdf() *splitTestSdf {
	return &splitTestSdf{
		proc: make(chan struct{}),
		rt:   make(chan *splitTestRTracker),
	}
}

func (fn *splitTestSdf) ProcessElement(rt *splitTestRTracker, _ int, emit func(offsetrange.Restriction, int)) {
	i := rt.GetRestriction().Start
	fn.proc <- struct{}{}

	for rt.TryClaim(i) == true {
		rest := rt.GetRestriction()
		emit(rest, int(i))
		i++
	}
}

func (fn *splitTestSdf) CreateInitialRestriction(i int) offsetrange.Restriction {
	return offsetrange.Restriction{
		Start: 0,
		End:   int64(i),
	}
}

func (fn *splitTestSdf) SplitRestriction(i int, rest offsetrange.Restriction) []offsetrange.Restriction {
	return []offsetrange.Restriction{rest}
}

func (fn *splitTestSdf) RestrictionSize(i int, rest offsetrange.Restriction) float64 {
	return rest.Size()
}

func (fn *splitTestSdf) CreateTracker(rest offsetrange.Restriction) *splitTestRTracker {
	rt := newSplitTestRTracker(rest)
	fn.rt <- rt
	return rt
}
