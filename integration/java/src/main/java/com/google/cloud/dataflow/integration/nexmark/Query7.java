/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.integration.nexmark;

import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Max;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;

import org.joda.time.Duration;

/**
 * Query 7, 'Highest Bid'. Select the bids with the highest bid
 * price in the last minute. In CQL syntax:
 *
 * <pre>
 * SELECT Rstream(B.auction, B.price, B.bidder)
 * FROM Bid [RANGE 1 MINUTE SLIDE 1 MINUTE] B
 * WHERE B.price = (SELECT MAX(B1.price)
 *                  FROM BID [RANGE 1 MINUTE SLIDE 1 MINUTE] B1);
 * </pre>
 *
 * <p>We will use a shorter window to help make testing easier. We'll also implement this using
 * a side-input in order to exercise that functionality. (A combiner, as used in Query 5, is
 * a more efficient approach.).
 */
class Query7 extends NexmarkQuery {
  public Query7(NexmarkConfiguration configuration) {
    super(configuration, "Query7");
  }

  private PCollection<Bid> applyTyped(PCollection<Event> events) {
    // Window the bids.
    PCollection<Bid> slidingBids = events.apply(JUST_BIDS).apply(
        Window.<Bid>into(FixedWindows.of(Duration.standardSeconds(configuration.windowSizeSec))));

    // Find the largest price in all bids.
    // NOTE: It would be more efficient to write this query much as we did for Query5, using
    // a binary combiner to accumulate the bids with maximal price. As written this query
    // requires an additional scan per window, with the associated cost of snapshotted state and
    // its I/O. We'll keep this implementation since it illustrates the use of side inputs.
    final PCollectionView<Long> maxPriceView =
        slidingBids //
            .apply(BID_TO_PRICE)
            .apply(Max.longsGlobally().withFanout(configuration.fanout).asSingletonView());

    return slidingBids
        // Select all bids which have that maximum price (there may be more than one).
        .apply(
            ParDo.named(name + ".Select")
                .withSideInputs(maxPriceView)
                .of(new DoFn<Bid, Bid>() {
                  @Override
                  public void processElement(ProcessContext c) {
                    long maxPrice = c.sideInput(maxPriceView);
                    Bid bid = c.element();
                    if (bid.price == maxPrice) {
                      c.output(bid);
                    }
                  }
                }));
  }

  @Override
  protected PCollection<KnownSize> applyPrim(PCollection<Event> events) {
    return NexmarkUtils.castToKnownSize(name, applyTyped(events));
  }
}
