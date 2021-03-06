package com.outbrain.aletheia.datum.consumption;

import com.google.common.collect.Lists;
import com.outbrain.aletheia.metrics.common.MetricsFactory;

import java.util.List;

/**
 * A {@code DatumEnvelopeFetcherFactory} for building {@code DatumEnvelopeFetcher}s capable of
 * consuming data from endpoints of type {@code ManualFeedConsumptionEndPoint}.
 */
public class ManualFeedDatumEnvelopeFetcherFactory implements DatumEnvelopeFetcherFactory<ManualFeedConsumptionEndPoint> {

  @Override
  public List<DatumEnvelopeFetcher> buildDatumEnvelopeFetcher(final ManualFeedConsumptionEndPoint consumptionEndPoint,
                                                              final MetricsFactory metricFactory) {
    return Lists.<DatumEnvelopeFetcher>newArrayList(new ManualFeedDatumEnvelopeFetcher(consumptionEndPoint,
                                                                                       metricFactory));
  }
}
