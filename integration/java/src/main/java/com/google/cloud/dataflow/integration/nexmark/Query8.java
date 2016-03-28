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
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResult;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.transforms.join.KeyedPCollectionTuple;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;

import org.joda.time.Duration;

/**
 * Query 8, 'Monitor New Users'. Select people who have entered the system and created auctions
 * in the last 12 hours, updated every 12 hours. In CQL syntax:
 *
 * <pre>
 * SELECT Rstream(P.id, P.name, A.reserve)
 * FROM Person [RANGE 12 HOUR] P, Auction [RANGE 12 HOUR] A
 * WHERE P.id = A.seller;
 * </pre>
 *
 * <p>To make things a bit more dynamic and easier to test we'll use a much shorter window.
 */
class Query8 extends NexmarkQuery {
  public Query8(NexmarkConfiguration configuration) {
    super(configuration, "Query8");
  }

  private PCollection<IdNameReserve> applyTyped(PCollection<Event> events) {
    // Window and key new people by their id.
    PCollection<KV<Long, Person>> personsById =
        events.apply(JUST_NEW_PERSONS)
            .apply(Window.<Person>into(
                             FixedWindows.of(Duration.standardSeconds(configuration.windowSizeSec)))
                    .named("Query8.WindowPersons"))
            .apply(PERSON_BY_ID);

    // Window and key new auctions by their id.
    PCollection<KV<Long, Auction>> auctionsBySeller =
        events.apply(JUST_NEW_AUCTIONS)
            .apply(Window.<Auction>into(
                             FixedWindows.of(Duration.standardSeconds(configuration.windowSizeSec)))
                    .named("Query8.WindowAuctions"))
            .apply(AUCTION_BY_SELLER);

    // Join people and auctions and project the person id, name and auction reserve price.
    return KeyedPCollectionTuple.of(PERSON_TAG, personsById)
        .and(AUCTION_TAG, auctionsBySeller)
        .apply(CoGroupByKey.<Long>create())
        .apply(
            ParDo.named(name + ".Select")
                .of(new DoFn<KV<Long, CoGbkResult>, IdNameReserve>() {
                  @Override
                  public void processElement(ProcessContext c) {
                    Person person = c.element().getValue().getOnly(PERSON_TAG, null);
                    if (person == null) {
                      // Person was not created in last window period.
                      return;
                    }
                    for (Auction auction : c.element().getValue().getAll(AUCTION_TAG)) {
                      c.output(new IdNameReserve(person.id, person.name, auction.reserve));
                    }
                  }
                }));
  }

  @Override
  protected PCollection<KnownSize> applyPrim(PCollection<Event> events) {
    return NexmarkUtils.castToKnownSize(name, applyTyped(events));
  }
}
