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

import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterPane;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Repeatedly;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.common.collect.Lists;

import org.joda.time.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Query 6, 'Average Selling Price by Seller'. Select the average selling price over the
 * last 10 closed auctions by the same seller. In CQL syntax:
 *
 * <pre>
 * SELECT Istream(AVG(Q.final), Q.seller)
 * FROM (SELECT Rstream(MAX(B.price) AS final, A.seller)
 *       FROM Auction A [ROWS UNBOUNDED], Bid B [ROWS UNBOUNDED]
 *       WHERE A.id=B.auction AND B.datetime < A.expires AND A.expires < CURRENT_TIME
 *       GROUP BY A.id, A.seller) [PARTITION BY A.seller ROWS 10] Q
 * GROUP BY Q.seller;
 * </pre>
 *
 * <p>We are a little more exact with selecting winning bids: see {@link WinningBids}.
 */
class Query6 extends NexmarkQuery {
  /**
   * Combiner to keep track of up to {@code maxNumBids} of the most recent wining bids and calculate
   * their average selling price.
   */
  private static class MovingMeanSellingPrice extends Combine.CombineFn<Bid, List<Bid>, Long> {
    private final int maxNumBids;

    public MovingMeanSellingPrice(int maxNumBids) {
      this.maxNumBids = maxNumBids;
    }

    @Override
    public List<Bid> createAccumulator() {
      return new ArrayList<>();
    }

    @Override
    public List<Bid> addInput(List<Bid> accumulator, Bid input) {
      accumulator.add(input);
      Collections.sort(accumulator, Bid.ASCENDING_TIME_THEN_PRICE);
      if (accumulator.size() > maxNumBids) {
        accumulator.remove(0);
      }
      return accumulator;
    }

    @Override
    public List<Bid> mergeAccumulators(Iterable<List<Bid>> accumulators) {
      List<Bid> result = new ArrayList<>();
      for (List<Bid> accumulator : accumulators) {
        for (Bid bid : accumulator) {
          result.add(bid);
        }
      }
      Collections.sort(result, Bid.ASCENDING_TIME_THEN_PRICE);
      if (result.size() > maxNumBids) {
        result = Lists.newArrayList(result.listIterator(result.size() - maxNumBids));
      }
      return result;
    }

    @Override
    public Long extractOutput(List<Bid> accumulator) {
      if (accumulator.isEmpty()) {
        return 0L;
      }
      long sumOfPrice = 0;
      for (Bid bid : accumulator) {
        sumOfPrice += bid.price;
      }
      return Math.round((double) sumOfPrice / accumulator.size());
    }
  }

  public Query6(NexmarkConfiguration configuration) {
    super(configuration, "Query6");
  }

  private PCollection<SellerPrice> applyTyped(PCollection<Event> events) {
    return events
        // Find the winning bid for each closed auction.
        .apply(new WinningBids(name + ".WinningBids", configuration))

        // Key the winning bid by the seller id.
        .apply(
            ParDo.named(name + ".Rekey")
                .of(new DoFn<AuctionBid, KV<Long, Bid>>() {
                  @Override
                  public void processElement(ProcessContext c) {
                    Auction auction = c.element().auction;
                    Bid bid = c.element().bid;
                    c.output(KV.of(auction.seller, bid));
                  }
                }))

        // Re-window to update on every wining bid.
        .apply(
            Window.<KV<Long, Bid>>into(new GlobalWindows())
                .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1)))
                .accumulatingFiredPanes()
                .withAllowedLateness(Duration.ZERO))

        // Find the average of last 10 winning bids for each seller.
        .apply(Combine.<Long, Bid, Long>perKey(new MovingMeanSellingPrice(10)))

        // Project into our datatype.
        .apply(
            ParDo.named(name + ".Select")
                .of(new DoFn<KV<Long, Long>, SellerPrice>() {
                  @Override
                  public void processElement(ProcessContext c) {
                    c.output(new SellerPrice(c.element().getKey(), c.element().getValue()));
                  }
                }));
  }

  @Override
  protected PCollection<KnownSize> applyPrim(PCollection<Event> events) {
    return NexmarkUtils.castToKnownSize(name, applyTyped(events));
  }
}
