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

package com.spotify.heroic.metadata.memory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.async.AsyncObservable;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.Groups;
import com.spotify.heroic.common.RangeFilter;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.metadata.CountSeries;
import com.spotify.heroic.metadata.DeleteSeries;
import com.spotify.heroic.metadata.FindKeys;
import com.spotify.heroic.metadata.FindSeries;
import com.spotify.heroic.metadata.FindTags;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metric.WriteResult;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import lombok.ToString;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@MemoryScope
@ToString(exclude = {"async", "storage"})
public class MemoryBackend implements MetadataBackend {
    private final AsyncFramework async;
    private final Groups groups;
    private final Set<Series> storage;

    @Inject
    public MemoryBackend(
        final AsyncFramework async, final Groups groups, @Named("storage") final Set<Series> storage
    ) {
        this.async = async;
        this.groups = groups;
        this.storage = storage;
    }

    @Override
    public Groups groups() {
        return groups;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public AsyncFuture<Void> configure() {
        return async.resolved();
    }

    @Override
    public AsyncFuture<WriteResult> write(Series series, DateRange range) {
        this.storage.add(series);
        return async.resolved(WriteResult.EMPTY);
    }

    @Override
    public AsyncFuture<FindTags> findTags(RangeFilter filter) {
        final Map<String, Set<String>> tags = new HashMap<>();

        lookup(filter).forEach(s -> {
            for (final Map.Entry<String, String> e : s.getTags().entrySet()) {
                Set<String> values = tags.get(e.getKey());

                if (values != null) {
                    values.add(e.getValue());
                    continue;
                }

                values = new HashSet<>();
                values.add(e.getValue());
                tags.put(e.getKey(), values);
            }
        });

        return async.resolved(FindTags.of(tags, tags.size()));
    }

    @Override
    public AsyncFuture<FindSeries> findSeries(RangeFilter filter) {
        final Set<Series> s = ImmutableSet.copyOf(lookup(filter).iterator());
        return async.resolved(FindSeries.of(s, s.size(), 0));
    }

    @Override
    public AsyncFuture<CountSeries> countSeries(RangeFilter filter) {
        return async.resolved(
            new CountSeries(ImmutableList.of(), lookupFilter(filter).count(), false));
    }

    @Override
    public AsyncFuture<DeleteSeries> deleteSeries(RangeFilter filter) {
        final int deletes = (int) lookup(filter).map(storage::remove).filter(b -> b).count();
        return async.resolved(DeleteSeries.of(deletes, 0));
    }

    @Override
    public AsyncFuture<FindKeys> findKeys(RangeFilter filter) {
        final Set<String> keys = ImmutableSet.copyOf(lookup(filter).map(Series::getKey).iterator());
        return async.resolved(FindKeys.of(keys, keys.size(), 0));
    }

    @Override
    public AsyncObservable<List<Series>> entries(RangeFilter filter) {
        return observer -> observer
            .observe(ImmutableList.copyOf(lookup(filter).iterator()))
            .onFinished(observer::end);
    }

    private Stream<Series> lookupFilter(final RangeFilter filter) {
        return storage.stream().filter(filter.getFilter()::apply);
    }

    private Stream<Series> lookup(final RangeFilter filter) {
        final Stream<Series> series = lookupFilter(filter);
        return filter.getLimit().asLong().map(series::limit).orElse(series);
    }
}
