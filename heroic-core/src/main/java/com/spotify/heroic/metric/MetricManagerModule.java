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

package com.spotify.heroic.metric;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.analytics.AnalyticsComponent;
import com.spotify.heroic.analytics.MetricAnalytics;
import com.spotify.heroic.common.GroupSet;
import com.spotify.heroic.common.ModuleIdBuilder;
import com.spotify.heroic.dagger.CorePrimaryComponent;
import com.spotify.heroic.lifecycle.LifeCycle;
import com.spotify.heroic.metadata.MetadataComponent;
import com.spotify.heroic.metadata.MetadataManager;
import com.spotify.heroic.statistics.HeroicReporter;
import com.spotify.heroic.statistics.MetricBackendReporter;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import eu.toolchain.async.AsyncFramework;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.spotify.heroic.common.Optionals.mergeOptionalList;
import static com.spotify.heroic.common.Optionals.pickOptional;
import static java.util.Optional.empty;
import static java.util.Optional.of;

@RequiredArgsConstructor
public class MetricManagerModule {
    public static final int DEFAULT_GROUP_LIMIT = 500;
    public static final long DEFAULT_SERIES_LIMIT = 10000;
    public static final long DEFAULT_AGGREGATION_LIMIT = 10000;
    public static final long DEFAULT_DATA_LIMIT = 30000000;
    public static final int DEFAULT_FETCH_PARALLELISM = 100;

    private final List<MetricModule> backends;
    private final Optional<List<String>> defaultBackends;

    /**
     * Limit in how many groups we are allowed to return.
     */
    private final int groupLimit;

    /**
     * Limit in the number of series we may fetch from the metadata backend.
     */
    private final long seriesLimit;

    /**
     * Limit in how many datapoints a single aggregation is allowed to output.
     */
    private final long aggregationLimit;

    /**
     * Limit in how many datapoints a session is allowed to fetch in total.
     */
    private final long dataLimit;

    /**
     * How many data fetches are performed in parallel.
     */
    private final int fetchParallelism;

    public MetricComponent module(
        final CorePrimaryComponent primary, final MetadataComponent metadata,
        final AnalyticsComponent analytics
    ) {
        return DaggerMetricManagerModule_C
            .builder()
            .corePrimaryComponent(primary)
            .m(new M(backends, defaultBackends, groupLimit, seriesLimit, aggregationLimit,
                dataLimit, fetchParallelism, primary))
            .metadataComponent(metadata)
            .analyticsComponent(analytics)
            .build();
    }

    @MetricScope
    @Component(modules = M.class,
        dependencies = {
            CorePrimaryComponent.class, MetadataComponent.class, AnalyticsComponent.class
        })
    interface C extends MetricComponent {
        @Override
        MetricManager metricManager();

        @Override
        @Named("metric")
        LifeCycle metricLife();
    }

    @RequiredArgsConstructor
    @Module
    public static class M {
        private final List<MetricModule> backends;
        private final Optional<List<String>> defaultBackends;
        private final int groupLimit;
        private final long seriesLimit;
        private final long aggregationLimit;
        private final long dataLimit;
        private final int fetchParallelism;
        private final CorePrimaryComponent primary;

        @Provides
        @MetricScope
        public MetricBackendReporter reporter(HeroicReporter reporter) {
            return reporter.newMetricBackend();
        }

        @Provides
        @MetricScope
        public GroupSet<MetricBackend> defaultBackends(
            Set<MetricBackend> configured, MetricAnalytics analytics
        ) {
            return GroupSet.build(
                ImmutableSet.copyOf(configured.stream().map(analytics::wrap).iterator()),
                defaultBackends);
        }

        @Provides
        @MetricScope
        public List<MetricModule.Exposed> components(final MetricBackendReporter reporter) {
            final List<MetricModule.Exposed> backends = new ArrayList<>();

            final ModuleIdBuilder idBuilder = new ModuleIdBuilder();

            for (final MetricModule m : this.backends) {
                final String id = idBuilder.buildId(m);

                final MetricModule.Depends depends = new MetricModule.Depends(reporter);
                backends.add(m.module(primary, depends, id));
            }

            return backends;
        }

        @Provides
        @MetricScope
        public Set<MetricBackend> backends(
            List<MetricModule.Exposed> components, MetricBackendReporter reporter
        ) {
            return ImmutableSet.copyOf(components
                .stream()
                .map(MetricModule.Exposed::backend)
                .map(reporter::decorate)
                .iterator());
        }

        @Provides
        @MetricScope
        @Named("metric")
        public LifeCycle metricLife(List<MetricModule.Exposed> components) {
            return LifeCycle.combined(components.stream().map(MetricModule.Exposed::life));
        }

        @Provides
        @MetricScope
        public MetricManager metricManager(
            final AsyncFramework async, final GroupSet<MetricBackend> backends,
            final MetadataManager metadata, final MetricBackendReporter reporter
        ) {
            return new LocalMetricManager(groupLimit, seriesLimit, aggregationLimit, dataLimit,
                fetchParallelism, async, backends, metadata, reporter);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor
    public static class Builder {
        private Optional<List<MetricModule>> backends = empty();
        private Optional<List<String>> defaultBackends = empty();
        private Optional<Integer> groupLimit = empty();
        private Optional<Long> seriesLimit = empty();
        private Optional<Long> aggregationLimit = empty();
        private Optional<Long> dataLimit = empty();
        private Optional<Integer> fetchParallelism = empty();

        public Builder backends(List<MetricModule> backends) {
            this.backends = of(backends);
            return this;
        }

        public Builder defaultBackends(List<String> defaultBackends) {
            this.defaultBackends = of(defaultBackends);
            return this;
        }

        public Builder groupLimit(int groupLimit) {
            this.groupLimit = of(groupLimit);
            return this;
        }

        public Builder seriesLimit(long seriesLimit) {
            this.seriesLimit = of(seriesLimit);
            return this;
        }

        public Builder aggregationLimit(Long aggregationLimit) {
            this.aggregationLimit = of(aggregationLimit);
            return this;
        }

        public Builder dataLimit(Long dataLimit) {
            this.dataLimit = of(dataLimit);
            return this;
        }

        public Builder fetchParallelism(Integer fetchParallelism) {
            this.fetchParallelism = of(fetchParallelism);
            return this;
        }

        public Builder merge(final Builder o) {
            // @formatter:off
            return new Builder(
                mergeOptionalList(o.backends, backends),
                mergeOptionalList(o.defaultBackends, defaultBackends),
                pickOptional(groupLimit, o.groupLimit),
                pickOptional(seriesLimit, o.seriesLimit),
                pickOptional(aggregationLimit, o.aggregationLimit),
                pickOptional(dataLimit, o.dataLimit),
                pickOptional(fetchParallelism, o.fetchParallelism)
            );
            // @formatter:on
        }

        public MetricManagerModule build() {
            // @formatter:off
            return new MetricManagerModule(
                backends.orElseGet(ImmutableList::of),
                defaultBackends,
                groupLimit.orElse(DEFAULT_GROUP_LIMIT),
                seriesLimit.orElse(DEFAULT_SERIES_LIMIT),
                aggregationLimit.orElse(DEFAULT_AGGREGATION_LIMIT),
                dataLimit.orElse(DEFAULT_DATA_LIMIT),
                fetchParallelism.orElse(DEFAULT_FETCH_PARALLELISM)
            );
            // @formatter:on
        }
    }
}
