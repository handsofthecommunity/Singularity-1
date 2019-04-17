package com.hubspot.singularity.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;

import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.singularity.SingularityCreateResult;
import com.hubspot.singularity.SingularityDeleteResult;
import com.hubspot.singularity.SingularityDeployKey;
import com.hubspot.singularity.SingularityDeployUpdate;
import com.hubspot.singularity.SingularityRequestHistory;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.SingularityWebhook;
import com.hubspot.singularity.SingularityWebhookSummary;
import com.hubspot.singularity.WebhookType;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.transcoders.Transcoder;

@Singleton
public class WebhookManager extends CuratorAsyncManager {

  private static final String ROOT_PATH = "/hooks";
  private static final String QUEUES_PATH = ROOT_PATH + "/queues";
  private static final String ACTIVE_PATH = ROOT_PATH + "/active";

  private static final String SNS_RETRY_ROOT = ROOT_PATH + "/snsretry";
  private static final String SNS_TASK_RETRY = SNS_RETRY_ROOT + "/task";
  private static final String SNS_REQUEST_RETRY = SNS_RETRY_ROOT + "/request";
  private static final String SNS_DEPLOY_RETRY = SNS_RETRY_ROOT + "/deploy";

  private final Transcoder<SingularityWebhook> webhookTranscoder;
  private final Transcoder<SingularityRequestHistory> requestHistoryTranscoder;
  private final Transcoder<SingularityTaskHistoryUpdate> taskHistoryUpdateTranscoder;
  private final Transcoder<SingularityDeployUpdate> deployWebhookTranscoder;

  private final Cache<WebhookType, List<SingularityWebhook>> activeWebhooksCache;

  @Inject
  public WebhookManager(CuratorFramework curator, SingularityConfiguration configuration, MetricRegistry metricRegistry, Transcoder<SingularityWebhook> webhookTranscoder,
      Transcoder<SingularityRequestHistory> requestHistoryTranscoder, Transcoder<SingularityTaskHistoryUpdate> taskHistoryUpdateTranscoder, Transcoder<SingularityDeployUpdate> deployWebhookTranscoder) {
    super(curator, configuration, metricRegistry);

    this.webhookTranscoder = webhookTranscoder;
    this.taskHistoryUpdateTranscoder = taskHistoryUpdateTranscoder;
    this.requestHistoryTranscoder = requestHistoryTranscoder;
    this.deployWebhookTranscoder = deployWebhookTranscoder;

    this.activeWebhooksCache = CacheBuilder.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build();
  }

  public List<SingularityWebhook> getActiveWebhooksUncached() {
    return getAsyncChildren(ACTIVE_PATH, webhookTranscoder);
  }

  public Iterable<SingularityWebhook> getActiveWebhooksByType(final WebhookType type) {
    List<SingularityWebhook> maybeCached = activeWebhooksCache.getIfPresent(type);
    if (maybeCached != null) {
      return maybeCached;
    } else {
      List<SingularityWebhook> webhooks = getActiveWebhooksUncached();
      List<SingularityWebhook> forType = webhooks.stream()
          .filter((w) -> w.getType() == type)
          .collect(Collectors.toList());
      activeWebhooksCache.put(type, forType);
      return forType;
    }
  }

  private String getTaskHistoryUpdateId(SingularityTaskHistoryUpdate taskUpdate) {
    return taskUpdate.getTaskId() + "-" + taskUpdate.getTaskState().name();
  }

  private String getRequestHistoryUpdateId(SingularityRequestHistory requestUpdate) {
    return requestUpdate.getRequest().getId() + "-" + requestUpdate.getEventType().name() + "-" + requestUpdate.getCreatedAt();
  }

  private String getDeployUpdateId(SingularityDeployUpdate deployUpdate) {
    return SingularityDeployKey.fromDeployMarker(deployUpdate.getDeployMarker()) + "-" + deployUpdate.getEventType().name();
  }

  private String getWebhookPath(String webhookId) {
    return ZKPaths.makePath(ACTIVE_PATH, webhookId);
  }

  private String getEnqueuePathForWebhook(String webhookId, WebhookType type) {
    return ZKPaths.makePath(ZKPaths.makePath(QUEUES_PATH, type.name()), webhookId);
  }

  private String getEnqueuePathForRequestUpdate(String webhookId, SingularityRequestHistory requestUpdate) {
    return ZKPaths.makePath(getEnqueuePathForWebhook(webhookId, WebhookType.REQUEST), getRequestHistoryUpdateId(requestUpdate));
  }

  private String getEnqueuePathForTaskUpdate(String webhookId, SingularityTaskHistoryUpdate taskUpdate) {
    return ZKPaths.makePath(getEnqueuePathForWebhook(webhookId, WebhookType.TASK), getTaskHistoryUpdateId(taskUpdate));
  }

  private String getEnqueuePathForDeployUpdate(String webhookId, SingularityDeployUpdate deployUpdate) {
    return ZKPaths.makePath(getEnqueuePathForWebhook(webhookId, WebhookType.DEPLOY), getDeployUpdateId(deployUpdate));
  }

  public SingularityCreateResult addWebhook(SingularityWebhook webhook) {
    final String path = getWebhookPath(webhook.getId());

    return create(path, webhook, webhookTranscoder);
  }

  public SingularityDeleteResult deleteWebhook(String webhookId) {
    final String path = getWebhookPath(webhookId);

    return delete(path);
  }

  public SingularityDeleteResult deleteTaskUpdate(SingularityWebhook webhook, SingularityTaskHistoryUpdate taskUpdate) {
    final String path = getEnqueuePathForTaskUpdate(webhook.getId(), taskUpdate);

    return delete(path);
  }

  public SingularityDeleteResult deleteDeployUpdate(SingularityWebhook webhook, SingularityDeployUpdate deployUpdate) {
    final String path = getEnqueuePathForDeployUpdate(webhook.getId(), deployUpdate);

    return delete(path);
  }

  public SingularityDeleteResult deleteRequestUpdate(SingularityWebhook webhook, SingularityRequestHistory requestUpdate) {
    final String path = getEnqueuePathForRequestUpdate(webhook.getId(), requestUpdate);

    return delete(path);
  }

  public List<SingularityDeployUpdate> getQueuedDeployUpdatesForHook(String webhookId) {
    return getAsyncChildren(getEnqueuePathForWebhook(webhookId, WebhookType.DEPLOY), deployWebhookTranscoder);
  }

  public List<SingularityTaskHistoryUpdate> getQueuedTaskUpdatesForHook(String webhookId) {
    return getAsyncChildren(getEnqueuePathForWebhook(webhookId, WebhookType.TASK), taskHistoryUpdateTranscoder);
  }

  public List<SingularityRequestHistory> getQueuedRequestHistoryForHook(String webhookId) {
    return getAsyncChildren(getEnqueuePathForWebhook(webhookId, WebhookType.REQUEST), requestHistoryTranscoder);
  }

  public List<SingularityWebhookSummary> getWebhooksWithQueueSize() {
    List<SingularityWebhookSummary> webhooks = new ArrayList<>();
    for (SingularityWebhook webhook : getActiveWebhooksUncached()) {
      webhooks.add(new SingularityWebhookSummary(webhook, getNumChildren(getEnqueuePathForWebhook(webhook.getId(), webhook.getType()))));
    }
    return webhooks;
  }

  void saveRequestHistoryEvent(SingularityRequestHistory requestUpdate) {
    for (SingularityWebhook webhook : getActiveWebhooksByType(WebhookType.REQUEST)) {
      final String enqueuePath = getEnqueuePathForRequestUpdate(webhook.getId(), requestUpdate);

      save(enqueuePath, requestUpdate, requestHistoryTranscoder);
    }
  }

  void saveTaskHistoryUpdateEvent(SingularityTaskHistoryUpdate taskUpdate) {
    for (SingularityWebhook webhook : getActiveWebhooksByType(WebhookType.TASK)) {
      final String enqueuePath = getEnqueuePathForTaskUpdate(webhook.getId(), taskUpdate);

      save(enqueuePath, taskUpdate, taskHistoryUpdateTranscoder);
    }
  }

  void saveDeployHistoryEvent(SingularityDeployUpdate deployUpdate) {
    for (SingularityWebhook webhook : getActiveWebhooksByType(WebhookType.DEPLOY)) {
      final String enqueuePath = getEnqueuePathForDeployUpdate(webhook.getId(), deployUpdate);

      save(enqueuePath, deployUpdate, deployWebhookTranscoder);
    }
  }

  // Methods for use with sns poller
  public void saveTaskUpdateForRetry(SingularityTaskHistoryUpdate taskHistoryUpdate) {
    String updatePath = ZKPaths.makePath(SNS_TASK_RETRY, getTaskHistoryUpdateId(taskHistoryUpdate));
    save(updatePath, taskHistoryUpdate, taskHistoryUpdateTranscoder);
  }

  public List<SingularityTaskHistoryUpdate> getTaskUpdatesToRetry() {
    return getAsyncChildren(SNS_TASK_RETRY, taskHistoryUpdateTranscoder);
  }

  public void saveDeployUpdateForRetry(SingularityDeployUpdate deployUpdate) {
    String updatePath = ZKPaths.makePath(SNS_DEPLOY_RETRY, getDeployUpdateId(deployUpdate));
    save(updatePath, deployUpdate, deployWebhookTranscoder);
  }

  public List<SingularityDeployUpdate> getDeployUpdatesToRetry() {
    return getAsyncChildren(SNS_DEPLOY_RETRY, deployWebhookTranscoder);
  }

  public void saveRequestUpdateForRetry(SingularityRequestHistory requestHistory) {
    String updatePath = ZKPaths.makePath(SNS_REQUEST_RETRY, getRequestHistoryUpdateId(requestHistory));
    save(updatePath, requestHistory, requestHistoryTranscoder);
  }

  public List<SingularityRequestHistory> getRequestUpdatesToRetry() {
    return getAsyncChildren(SNS_REQUEST_RETRY, requestHistoryTranscoder);
  }
}
