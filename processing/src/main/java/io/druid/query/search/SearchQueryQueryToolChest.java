/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.query.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import com.metamx.common.IAE;
import com.metamx.common.ISE;
import com.metamx.common.guava.MergeSequence;
import com.metamx.common.guava.Sequence;
import com.metamx.common.guava.Sequences;
import com.metamx.common.guava.nary.BinaryFn;
import com.metamx.emitter.service.ServiceMetricEvent;
import io.druid.collections.OrderedMergeSequence;
import io.druid.query.CacheStrategy;
import io.druid.query.IntervalChunkingQueryRunner;
import io.druid.query.Query;
import io.druid.query.QueryMetricUtil;
import io.druid.query.QueryRunner;
import io.druid.query.QueryToolChest;
import io.druid.query.Result;
import io.druid.query.ResultGranularTimestampComparator;
import io.druid.query.ResultMergeQueryRunner;
import io.druid.query.aggregation.MetricManipulationFn;
import io.druid.query.filter.DimFilter;
import io.druid.query.search.search.SearchHit;
import io.druid.query.search.search.SearchQuery;
import io.druid.query.search.search.SearchQueryConfig;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class SearchQueryQueryToolChest extends QueryToolChest<Result<SearchResultValue>, SearchQuery>
{
  private static final byte SEARCH_QUERY = 0x2;
  private static final TypeReference<Result<SearchResultValue>> TYPE_REFERENCE = new TypeReference<Result<SearchResultValue>>()
  {
  };
  private static final TypeReference<Object> OBJECT_TYPE_REFERENCE = new TypeReference<Object>()
  {
  };

  private final SearchQueryConfig config;

  @Inject
  public SearchQueryQueryToolChest(
      SearchQueryConfig config
  )
  {
    this.config = config;
  }

  @Override
  public QueryRunner<Result<SearchResultValue>> mergeResults(QueryRunner<Result<SearchResultValue>> runner)
  {
    return new ResultMergeQueryRunner<Result<SearchResultValue>>(runner)
    {
      @Override
      protected Ordering<Result<SearchResultValue>> makeOrdering(Query<Result<SearchResultValue>> query)
      {
        return Ordering.from(
            new ResultGranularTimestampComparator<SearchResultValue>(((SearchQuery) query).getGranularity())
        );
      }

      @Override
      protected BinaryFn<Result<SearchResultValue>, Result<SearchResultValue>, Result<SearchResultValue>> createMergeFn(
          Query<Result<SearchResultValue>> input
      )
      {
        SearchQuery query = (SearchQuery) input;
        return new SearchBinaryFn(query.getSort(), query.getGranularity(), query.getLimit());
      }
    };
  }

  @Override
  public Sequence<Result<SearchResultValue>> mergeSequences(Sequence<Sequence<Result<SearchResultValue>>> seqOfSequences)
  {
    return new OrderedMergeSequence<>(getOrdering(), seqOfSequences);
  }

  @Override
  public Sequence<Result<SearchResultValue>> mergeSequencesUnordered(Sequence<Sequence<Result<SearchResultValue>>> seqOfSequences)
  {
    return new MergeSequence<>(getOrdering(), seqOfSequences);
  }

  @Override
  public ServiceMetricEvent.Builder makeMetricBuilder(SearchQuery query)
  {
    return QueryMetricUtil.makeQueryTimeMetric(query);
  }

  @Override
  public Function<Result<SearchResultValue>, Result<SearchResultValue>> makePreComputeManipulatorFn(
      SearchQuery query, MetricManipulationFn fn
  )
  {
    return Functions.identity();
  }

  @Override
  public TypeReference<Result<SearchResultValue>> getResultTypeReference()
  {
    return TYPE_REFERENCE;
  }

  @Override
  public CacheStrategy<Result<SearchResultValue>, Object, SearchQuery> getCacheStrategy(SearchQuery query)
  {
    return new CacheStrategy<Result<SearchResultValue>, Object, SearchQuery>()
    {
      @Override
      public byte[] computeCacheKey(SearchQuery query)
      {
        final DimFilter dimFilter = query.getDimensionsFilter();
        final byte[] filterBytes = dimFilter == null ? new byte[]{} : dimFilter.getCacheKey();
        final byte[] querySpecBytes = query.getQuery().getCacheKey();
        final byte[] granularityBytes = query.getGranularity().cacheKey();

        final Set<String> dimensions = Sets.newTreeSet();
        if (query.getDimensions() != null) {
          dimensions.addAll(query.getDimensions());
        }

        final byte[][] dimensionsBytes = new byte[dimensions.size()][];
        int dimensionsBytesSize = 0;
        int index = 0;
        for (String dimension : dimensions) {
          dimensionsBytes[index] = dimension.getBytes(Charsets.UTF_8);
          dimensionsBytesSize += dimensionsBytes[index].length;
          ++index;
        }

        final ByteBuffer queryCacheKey = ByteBuffer
            .allocate(
                1 + 4 + granularityBytes.length + filterBytes.length +
                querySpecBytes.length + dimensionsBytesSize
            )
            .put(SEARCH_QUERY)
            .put(Ints.toByteArray(query.getLimit()))
            .put(granularityBytes)
            .put(filterBytes)
            .put(querySpecBytes);

        for (byte[] bytes : dimensionsBytes) {
          queryCacheKey.put(bytes);
        }

        return queryCacheKey.array();
      }

      @Override
      public TypeReference<Object> getCacheObjectClazz()
      {
        return OBJECT_TYPE_REFERENCE;
      }

      @Override
      public Function<Result<SearchResultValue>, Object> prepareForCache()
      {
        return new Function<Result<SearchResultValue>, Object>()
        {
          @Override
          public Object apply(Result<SearchResultValue> input)
          {
            return Lists.newArrayList(input.getTimestamp().getMillis(), input.getValue());
          }
        };
      }

      @Override
      public Function<Object, Result<SearchResultValue>> pullFromCache()
      {
        return new Function<Object, Result<SearchResultValue>>()
        {
          @Override
          @SuppressWarnings("unchecked")
          public Result<SearchResultValue> apply(Object input)
          {
            List<Object> result = (List<Object>) input;

            return new Result<SearchResultValue>(
                new DateTime(result.get(0)),
                new SearchResultValue(
                    Lists.transform(
                        (List) result.get(1),
                        new Function<Object, SearchHit>()
                        {
                          @Override
                          public SearchHit apply(@Nullable Object input)
                          {
                            if (input instanceof Map) {
                              return new SearchHit(
                                  (String) ((Map) input).get("dimension"),
                                  (String) ((Map) input).get("value")
                              );
                            } else if (input instanceof SearchHit) {
                              return (SearchHit) input;
                            } else {
                              throw new IAE("Unknown format [%s]", input.getClass());
                            }
                          }
                        }
                    )
                )
            );
          }
        };
      }

      @Override
      public Sequence<Result<SearchResultValue>> mergeSequences(Sequence<Sequence<Result<SearchResultValue>>> seqOfSequences)
      {
        return new MergeSequence<Result<SearchResultValue>>(getOrdering(), seqOfSequences);
      }
    };
  }

  @Override
  public QueryRunner<Result<SearchResultValue>> preMergeQueryDecoration(QueryRunner<Result<SearchResultValue>> runner)
  {
    return new SearchThresholdAdjustingQueryRunner(
        new IntervalChunkingQueryRunner<Result<SearchResultValue>>(runner, config.getChunkPeriod()),
        config
    );
  }

  public Ordering<Result<SearchResultValue>> getOrdering()
  {
    return Ordering.natural();
  }

  private static class SearchThresholdAdjustingQueryRunner implements QueryRunner<Result<SearchResultValue>>
  {
    private final QueryRunner<Result<SearchResultValue>> runner;
    private final SearchQueryConfig config;

    public SearchThresholdAdjustingQueryRunner(
        QueryRunner<Result<SearchResultValue>> runner,
        SearchQueryConfig config
    )
    {
      this.runner = runner;
      this.config = config;
    }

    @Override
    public Sequence<Result<SearchResultValue>> run(
        Query<Result<SearchResultValue>> input,
        Map<String, Object> context
    )
    {
      if (!(input instanceof SearchQuery)) {
        throw new ISE("Can only handle [%s], got [%s]", SearchQuery.class, input.getClass());
      }

      final SearchQuery query = (SearchQuery) input;
      if (query.getLimit() < config.getMaxSearchLimit()) {
        return runner.run(query, context);
      }

      final boolean isBySegment = query.getContextBySegment(false);

      return Sequences.map(
          runner.run(query.withLimit(config.getMaxSearchLimit()), context),
          new Function<Result<SearchResultValue>, Result<SearchResultValue>>()
          {
            @Override
            public Result<SearchResultValue> apply(Result<SearchResultValue> input)
            {
              if (isBySegment) {
                BySegmentSearchResultValue value = (BySegmentSearchResultValue) input.getValue();

                return new Result<SearchResultValue>(
                    input.getTimestamp(),
                    new BySegmentSearchResultValue(
                        Lists.transform(
                            value.getResults(),
                            new Function<Result<SearchResultValue>, Result<SearchResultValue>>()
                            {
                              @Override
                              public Result<SearchResultValue> apply(@Nullable Result<SearchResultValue> input)
                              {
                                return new Result<SearchResultValue>(
                                    input.getTimestamp(),
                                    new SearchResultValue(
                                        Lists.newArrayList(
                                            Iterables.limit(
                                                input.getValue(),
                                                query.getLimit()
                                            )
                                        )
                                    )
                                );
                              }
                            }
                        ),
                        value.getSegmentId(),
                        value.getInterval()
                    )
                );
              }

              return new Result<SearchResultValue>(
                  input.getTimestamp(),
                  new SearchResultValue(
                      Lists.<SearchHit>newArrayList(
                          Iterables.limit(input.getValue(), query.getLimit())
                      )
                  )
              );
            }
          }
      );
    }
  }
}
