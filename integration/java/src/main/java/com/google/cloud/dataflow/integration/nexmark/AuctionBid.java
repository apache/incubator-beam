/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.dataflow.integration.nexmark;

import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Result of {@link WinningBids} transform.
 */
public class AuctionBid implements KnownSize, Serializable {
  public static final Coder<AuctionBid> CODER = new AtomicCoder<AuctionBid>() {
    @Override
    public void encode(AuctionBid value, OutputStream outStream,
        com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      Auction.CODER.encode(value.auction, outStream, Context.NESTED);
      Bid.CODER.encode(value.bid, outStream, Context.NESTED);
    }

    @Override
    public AuctionBid decode(
        InputStream inStream, com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      Auction auction = Auction.CODER.decode(inStream, Context.NESTED);
      Bid bid = Bid.CODER.decode(inStream, Context.NESTED);
      return new AuctionBid(auction, bid);
    }
  };

  @JsonProperty
  public final Auction auction;

  @JsonProperty
  public final Bid bid;

  // For Avro only.
  @SuppressWarnings("unused")
  private AuctionBid() {
    auction = null;
    bid = null;
  }

  public AuctionBid(Auction auction, Bid bid) {
    this.auction = auction;
    this.bid = bid;
  }

  @Override
  public long sizeInBytes() {
    return auction.sizeInBytes() + bid.sizeInBytes();
  }

  @Override
  public String toString() {
    try {
      return NexmarkUtils.MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
