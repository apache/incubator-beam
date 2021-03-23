package org.apache.beam.examples.cookbook;

import org.apache.beam.examples.cookbook.BigQueryTornadoes;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.joda.time.Duration;

import java.io.IOException;

public class BigQueryIOMetricsPipeline {
  @DoFn.BoundedPerElement
  public static class SleepForever extends DoFn<Object, Void> {

    @GetInitialRestriction
    public OffsetRange getInitialRestriction(@Element Object element) throws IOException {
      return new OffsetRange(0, 1);
    }

    @ProcessElement
    public ProcessContinuation processElement(
      @Element Object element,
      RestrictionTracker<OffsetRange, Long> tracker)
      //OutputReceiver<Void> outputReceiver)
      throws IOException, InterruptedException {
      // Return a short delay if there is no data to process at the moment.
      return ProcessContinuation.resume().withResumeDelay(Duration.standardSeconds(10));
    }

    // Providing the coder is only necessary if it can not be inferred at runtime.
    @GetRestrictionCoder
    public Coder<OffsetRange> getRestrictionCoder() {
      return OffsetRange.Coder.of();
    }
  }

  // TODO add variant that reads via query, table, view. etc.
  public static void main(String args[]) {
    BigQueryTornadoes.Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(
        BigQueryTornadoes.Options.class);
    Pipeline p = BigQueryTornadoes.runBigQueryTornadoes(options);

    BigQueryTornadoes.Options optionsCopy = PipelineOptionsFactory.fromArgs(args).withValidation().as(
        BigQueryTornadoes.Options.class);

    optionsCopy.setOutput(options.getOutput() + "_not_found_expected");
    optionsCopy.setCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_NEVER);
    BigQueryTornadoes.applyBigQueryTornadoes(p, optionsCopy);

    p.apply(Create.of(1,2,3,4,5))
         .apply(ParDo.of(new SleepForever()));
    p.run().waitUntilFinish();
  }
}

