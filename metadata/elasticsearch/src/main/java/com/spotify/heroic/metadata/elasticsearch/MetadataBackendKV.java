/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metadata.elasticsearch;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.Groups;
import com.spotify.heroic.common.OptionalLimit;
import com.spotify.heroic.common.RangeFilter;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.common.Statistics;
import com.spotify.heroic.elasticsearch.AbstractElasticsearchMetadataBackend;
import com.spotify.heroic.elasticsearch.BackendType;
import com.spotify.heroic.elasticsearch.Connection;
import com.spotify.heroic.elasticsearch.RateLimitedCache;
import com.spotify.heroic.elasticsearch.index.NoIndexSelectedException;
import com.spotify.heroic.filter.AndFilter;
import com.spotify.heroic.filter.FalseFilter;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.filter.HasTagFilter;
import com.spotify.heroic.filter.MatchKeyFilter;
import com.spotify.heroic.filter.MatchTagFilter;
import com.spotify.heroic.filter.NotFilter;
import com.spotify.heroic.filter.OrFilter;
import com.spotify.heroic.filter.StartsWithFilter;
import com.spotify.heroic.filter.TrueFilter;
import com.spotify.heroic.lifecycle.LifeCycleRegistry;
import com.spotify.heroic.lifecycle.LifeCycles;
import com.spotify.heroic.metadata.CountSeries;
import com.spotify.heroic.metadata.DeleteSeries;
import com.spotify.heroic.metadata.FindKeys;
import com.spotify.heroic.metadata.FindSeries;
import com.spotify.heroic.metadata.FindTags;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metric.WriteResult;
import com.spotify.heroic.statistics.MetadataBackendReporter;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedAction;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;
import org.elasticsearch.action.count.CountRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.index.query.FilterBuilders.andFilter;
import static org.elasticsearch.index.query.FilterBuilders.matchAllFilter;
import static org.elasticsearch.index.query.FilterBuilders.notFilter;
import static org.elasticsearch.index.query.FilterBuilders.orFilter;
import static org.elasticsearch.index.query.FilterBuilders.prefixFilter;
import static org.elasticsearch.index.query.FilterBuilders.termFilter;

@ElasticsearchScope
@ToString(of = {"connection"})
public class MetadataBackendKV extends AbstractElasticsearchMetadataBackend
    implements MetadataBackend, LifeCycles {
    public static final String WRITE_CACHE_SIZE = "write-cache-size";

    static final String KEY = "key";
    static final String TAGS = "tags";
    static final String TAG_KEYS = "tag_keys";
    static final Character TAG_DELIMITER = '\0';

    static final String TYPE_METADATA = "metadata";

    static final TimeValue SCROLL_TIME = TimeValue.timeValueMillis(5000);
    static final int SCROLL_SIZE = 1000;

    private final Groups groups;
    private final MetadataBackendReporter reporter;
    private final AsyncFramework async;
    private final Managed<Connection> connection;
    private final RateLimitedCache<Pair<String, HashCode>> writeCache;
    private final boolean configure;

    @Inject
    public MetadataBackendKV(
        Groups groups, MetadataBackendReporter reporter, AsyncFramework async,
        Managed<Connection> connection, RateLimitedCache<Pair<String, HashCode>> writeCache,
        @Named("configure") boolean configure
    ) {
        super(async, TYPE_METADATA);
        this.groups = groups;
        this.reporter = reporter;
        this.async = async;
        this.connection = connection;
        this.writeCache = writeCache;
        this.configure = configure;
    }

    @Override
    public void register(LifeCycleRegistry registry) {
        registry.start(this::start);
        registry.stop(this::stop);
    }

    @Override
    protected Managed<Connection> connection() {
        return connection;
    }

    @Override
    public AsyncFuture<Void> configure() {
        return doto(Connection::configure);
    }

    @Override
    public Groups groups() {
        return groups;
    }

    @Override
    public AsyncFuture<FindTags> findTags(final RangeFilter filter) {
        return doto(c -> async.resolved(FindTags.EMPTY));
    }

    @Override
    public AsyncFuture<WriteResult> write(final Series series, final DateRange range) {
        return doto(c -> {
            final String id = series.hash();

            final String[] indices;

            try {
                indices = c.writeIndices(range);
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            final List<AsyncFuture<WriteResult>> writes = new ArrayList<>();

            for (final String index : indices) {
                if (!writeCache.acquire(Pair.of(index, series.getHashCode()))) {
                    reporter.reportWriteDroppedByRateLimit();
                    continue;
                }

                final XContentBuilder source = XContentFactory.jsonBuilder();

                source.startObject();
                buildContext(source, series);
                source.endObject();

                final IndexRequestBuilder request = c
                    .index(index, TYPE_METADATA)
                    .setId(id)
                    .setSource(source)
                    .setOpType(OpType.CREATE);

                final long start = System.nanoTime();
                AsyncFuture<WriteResult> result = bind(request.execute()).directTransform(
                    response -> WriteResult.of(System.nanoTime() - start));

                writes.add(result);
            }

            return async.collect(writes, WriteResult.merger());
        });
    }

    @Override
    public AsyncFuture<CountSeries> countSeries(final RangeFilter filter) {
        return doto(c -> {
            final OptionalLimit limit = filter.getLimit();

            if (limit.isZero()) {
                return async.resolved(CountSeries.of());
            }

            final FilterBuilder f = filter(filter.getFilter());

            final CountRequestBuilder request;

            try {
                request = c.count(filter.getRange(), TYPE_METADATA);
                limit.asInteger().ifPresent(request::setTerminateAfter);
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            request.setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

            return bind(request.execute()).directTransform(
                response -> CountSeries.of(response.getCount(), false));
        });
    }

    @Override
    public AsyncFuture<FindSeries> findSeries(final RangeFilter filter) {
        return doto(c -> {
            final OptionalLimit limit = filter.getLimit();

            if (limit.isZero()) {
                return async.resolved(FindSeries.EMPTY);
            }

            final FilterBuilder f = filter(filter.getFilter());

            final SearchRequestBuilder request;

            try {
                request = c
                    .search(filter.getRange(), TYPE_METADATA)
                    .setScroll(SCROLL_TIME)
                    .setSearchType(SearchType.SCAN);

                request.setSize(limit.asMaxInteger(SCROLL_SIZE));
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            request.setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

            return scrollOverSeries(c, request, filter.getLimit());
        });
    }

    @Override
    public AsyncFuture<DeleteSeries> deleteSeries(final RangeFilter filter) {
        return doto(c -> {
            final FilterBuilder f = filter(filter.getFilter());

            final DeleteByQueryRequestBuilder request;

            try {
                request = c.deleteByQuery(filter.getRange(), TYPE_METADATA);
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            request.setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

            return bind(request.execute()).directTransform(response -> DeleteSeries.of());
        });
    }

    @Override
    public AsyncFuture<FindKeys> findKeys(final RangeFilter filter) {
        return doto(c -> {
            final FilterBuilder f = filter(filter.getFilter());

            final SearchRequestBuilder request;

            try {
                request = c.search(filter.getRange(), TYPE_METADATA).setSearchType("count");
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            request.setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

            {
                final AggregationBuilder<?> terms =
                    AggregationBuilders.terms("terms").field(KEY).size(0);
                request.addAggregation(terms);
            }

            return bind(request.execute()).directTransform(response -> {
                final Terms terms = (Terms) response.getAggregations().get("terms");

                final Set<String> keys = new HashSet<String>();

                int size = terms.getBuckets().size();
                int duplicates = 0;

                for (final Terms.Bucket bucket : terms.getBuckets()) {
                    if (keys.add(bucket.getKey())) {
                        duplicates += 1;
                    }
                }

                return FindKeys.of(keys, size, duplicates);
            });
        });
    }

    @Override
    public boolean isReady() {
        return connection.isReady();
    }

    @Override
    protected Series toSeries(SearchHit hit) {
        final Map<String, Object> source = hit.getSource();
        final String key = (String) source.get(KEY);
        final Iterator<Map.Entry<String, String>> tags =
            ((List<String>) source.get(TAGS)).stream().map(this::buildTag).iterator();
        return Series.of(key, tags);
    }

    private <T> AsyncFuture<T> doto(ManagedAction<Connection, T> action) {
        return connection.doto(action);
    }

    AsyncFuture<Void> start() {
        final AsyncFuture<Void> future = connection.start();

        if (!configure) {
            return future;
        }

        return future.lazyTransform(v -> configure());
    }

    AsyncFuture<Void> stop() {
        return connection.stop();
    }

    Map.Entry<String, String> buildTag(String kv) {
        final int index = kv.indexOf(TAG_DELIMITER);

        if (index == -1) {
            throw new IllegalArgumentException("invalid tag source: " + kv);
        }

        final String tk = kv.substring(0, index);
        final String tv = kv.substring(index + 1);
        return Pair.of(tk, tv);
    }

    static void buildContext(final XContentBuilder b, Series series) throws IOException {
        b.field(KEY, series.getKey());

        b.startArray(TAGS);

        for (final Map.Entry<String, String> entry : series.getTags().entrySet()) {
            b.value(entry.getKey() + TAG_DELIMITER + entry.getValue());
        }

        b.endArray();

        b.startArray(TAG_KEYS);

        for (final Map.Entry<String, String> entry : series.getTags().entrySet()) {
            b.value(entry.getKey());
        }

        b.endArray();
    }

    private static final Filter.Visitor<FilterBuilder> FILTER_CONVERTER =
        new Filter.Visitor<FilterBuilder>() {
            @Override
            public FilterBuilder visitTrue(final TrueFilter t) {
                return matchAllFilter();
            }

            @Override
            public FilterBuilder visitFalse(final FalseFilter f) {
                return notFilter(matchAllFilter());
            }

            @Override
            public FilterBuilder visitAnd(final AndFilter and) {
                return andFilter(convertTerms(and.terms()));
            }

            @Override
            public FilterBuilder visitOr(final OrFilter or) {
                return orFilter(convertTerms(or.terms()));
            }

            @Override
            public FilterBuilder visitNot(final NotFilter not) {
                return notFilter(not.getFilter().visit(this));
            }

            @Override
            public FilterBuilder visitMatchTag(final MatchTagFilter matchTag) {
                return termFilter(TAGS, matchTag.getTag() + TAG_DELIMITER + matchTag.getValue());
            }

            @Override
            public FilterBuilder visitStartsWith(final StartsWithFilter startsWith) {
                return prefixFilter(TAGS,
                    startsWith.getTag() + TAG_DELIMITER + startsWith.getValue());
            }

            @Override
            public FilterBuilder visitHasTag(final HasTagFilter hasTag) {
                return termFilter(TAG_KEYS, hasTag.getTag());
            }

            @Override
            public FilterBuilder visitMatchKey(final MatchKeyFilter matchKey) {
                return termFilter(KEY, matchKey.getValue());
            }

            @Override
            public FilterBuilder defaultAction(final Filter filter) {
                throw new IllegalArgumentException("Unsupported filter statement: " + filter);
            }

            private FilterBuilder[] convertTerms(final List<Filter> terms) {
                final FilterBuilder[] filters = new FilterBuilder[terms.size()];
                int i = 0;

                for (final Filter stmt : terms) {
                    filters[i++] = stmt.visit(this);
                }

                return filters;
            }
        };

    @Override
    protected FilterBuilder filter(final Filter filter) {
        return filter.visit(FILTER_CONVERTER);
    }

    @Override
    public Statistics getStatistics() {
        return Statistics.of(WRITE_CACHE_SIZE, writeCache.size());
    }

    public static BackendType backendType() {
        final Map<String, Map<String, Object>> mappings = new HashMap<>();
        mappings.put("metadata", ElasticsearchMetadataUtils.loadJsonResource("kv/metadata.json"));
        return new BackendType(mappings, ImmutableMap.of(), MetadataBackendKV.class);
    }
}
