/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.settings;

import org.elasticsearch.action.admin.indices.close.TransportCloseIndexAction;
import org.elasticsearch.action.support.DestructiveOperations;
import org.elasticsearch.cluster.InternalClusterInfoService;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ConcurrentRebalanceAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ShardsLimitAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.SnapshotInProgressAllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import org.elasticsearch.cluster.service.InternalClusterService;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.gateway.PrimaryShardAllocator;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexingSlowLog;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.index.MergeSchedulerConfig;
import org.elasticsearch.index.SearchSlowLog;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.store.IndexStoreConfig;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.indices.cache.request.IndicesRequestCache;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.indices.ttl.IndicesTTLService;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates all valid cluster level settings.
 */
public final class IndexScopeSettings extends AbstractScopedSettings {

    public IndexScopeSettings(Settings settings, Set<Setting<?>> settingsSet) {
        super(settings, settingsSet, Setting.Scope.INDEX);
    }

    private IndexScopeSettings(Settings settings, IndexScopeSettings other, IndexMetaData metaData) {
        super(settings, metaData.getSettings(), other);
    }

    public static Set<Setting<?>> BUILT_IN_INDEX_SETTINGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        IndexSettings.INDEX_TTL_DISABLE_PURGE_SETTING,
        IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING,
        IndexStore.INDEX_STORE_THROTTLE_MAX_BYTES_PER_SEC_SETTING,
        MergeSchedulerConfig.AUTO_THROTTLE_SETTING,
        MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING,
        MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING,
        IndexMetaData.INDEX_ROUTING_EXCLUDE_GROUP_SETTING,
        IndexMetaData.INDEX_ROUTING_INCLUDE_GROUP_SETTING,
        IndexMetaData.INDEX_ROUTING_REQUIRE_GROUP_SETTING,
        IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS_SETTING,
        IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING,
        IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING,
        IndexMetaData.INDEX_SHADOW_REPLICAS_SETTING,
        IndexMetaData.INDEX_SHARED_FILESYSTEM_SETTING,
        IndexMetaData.INDEX_READ_ONLY_SETTING,
        IndexMetaData.INDEX_BLOCKS_READ_SETTING,
        IndexMetaData.INDEX_BLOCKS_WRITE_SETTING,
        IndexMetaData.INDEX_BLOCKS_METADATA_SETTING,
        IndexMetaData.INDEX_SHARED_FS_ALLOW_RECOVERY_ON_ANY_NODE_SETTING,
        IndexMetaData.INDEX_PRIORITY_SETTING,
        IndexMetaData.INDEX_DATA_PATH_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_DEBUG_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_WARN_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_INFO_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_FETCH_TRACE_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_WARN_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_DEBUG_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_INFO_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_THRESHOLD_QUERY_TRACE_SETTING,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_LEVEL,
        SearchSlowLog.INDEX_SEARCH_SLOWLOG_REFORMAT,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_WARN_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_DEBUG_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_INFO_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_TRACE_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_REFORMAT_SETTING,
        IndexingSlowLog.INDEX_INDEXING_SLOWLOG_MAX_SOURCE_CHARS_TO_LOG_SETTING,
        MergePolicyConfig.INDEX_COMPOUND_FORMAT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_EXPUNGE_DELETES_ALLOWED_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_EXPLICIT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGED_SEGMENT_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER_SETTING,
        MergePolicyConfig.INDEX_MERGE_POLICY_RECLAIM_DELETES_WEIGHT_SETTING,
        IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING,
        IndexSettings.INDEX_WARMER_ENABLED_SETTING,
        IndexSettings.INDEX_REFRESH_INTERVAL_SETTING,
        IndexSettings.MAX_RESULT_WINDOW_SETTING,
        ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING,
        IndexSettings.INDEX_GC_DELETES_SETTING,
        IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING,
        UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING,
        EnableAllocationDecider.INDEX_ROUTING_REBALANCE_ENABLE_SETTING,
        EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE_SETTING,
        IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE_SETTTING
    )));

    public IndexScopeSettings copy(Settings settings, IndexMetaData metaData) {
        return new IndexScopeSettings(settings, this, metaData);
    }
}
