/**
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

package org.apache.hadoop.hive.ql.exec.tez;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.registry.impl.TezAmInstance;
import org.apache.hive.common.util.Ref;

public class WmTezSession extends TezSessionPoolSession implements AmPluginNode {
  private String poolName;
  private double clusterFraction;
  /**
   * The reason to kill an AM. Note that this is for the entire session, not just for a query.
   * Once set, this can never be unset because you can only kill the session once.
   */
  private String killReason = null;

  private final Object amPluginInfoLock = new Object();
  private AmPluginInfo amPluginInfo = null;
  private Integer amPluginendpointVersion =  null;
  private SettableFuture<WmTezSession> amRegistryFuture = null;
  private ScheduledFuture<?> timeoutTimer = null;
  private String queryId;

  private final WorkloadManager wmParent;

  /** The actual state of the guaranteed task, and the update state, for the session. */
  // Note: hypothetically, a generic WM-aware-session should not know about guaranteed tasks.
  //       We should have another subclass for a WM-aware-session-implemented-using-ducks.
  //       However, since this is the only type of WM for now, this can live here.
  private final static class ActualWmState {
    // All accesses synchronized on the object itself. Could be replaced with CAS.
    int sending = -1, sent = -1, target = 0;
  }
  private final ActualWmState actualState = new ActualWmState();

  public WmTezSession(String sessionId, WorkloadManager parent,
      SessionExpirationTracker expiration, HiveConf conf) {
    super(sessionId, parent, expiration, conf);
    wmParent = parent;
  }

  @VisibleForTesting
  WmTezSession(String sessionId, Manager testParent,
      SessionExpirationTracker expiration, HiveConf conf) {
    super(sessionId, testParent, expiration, conf);
    wmParent = null;
  }

  public ListenableFuture<WmTezSession> waitForAmRegistryAsync(
      int timeoutMs, ScheduledExecutorService timeoutPool) {
    SettableFuture<WmTezSession> future = SettableFuture.create();
    synchronized (amPluginInfoLock) {
      if (amPluginInfo != null) {
        future.set(this);
        return future;
      }
      if (amRegistryFuture != null) {
        // We don't need this for now, so do not support it.
        future.setException(new RuntimeException("Multiple waits are not suported"));
        return future;
      }
      amRegistryFuture = future;
      if (timeoutMs <= 0) return future;
      // TODO: replace with withTimeout after we get the relevant guava upgrade.
      this.timeoutTimer = timeoutPool.schedule(
          new TimeoutRunnable(), timeoutMs, TimeUnit.MILLISECONDS);
    }
    return future;
  }


  @Override
  void updateFromRegistry(TezAmInstance si, int ephSeqVersion) {
    AmPluginInfo info = si == null ? null : new AmPluginInfo(si.getHost(), si.getPluginPort(),
        si.getPluginToken(), si.getPluginTokenJobId());
    synchronized (amPluginInfoLock) {
      // Ignore the outdated updates; for the same version, ignore non-null updates because
      // we assume that removal is the last thing that happens for any given version.
      if ((amPluginendpointVersion != null) && ((amPluginendpointVersion > ephSeqVersion)
          || (amPluginendpointVersion == ephSeqVersion && info != null))) {
        LOG.info("Ignoring an outdated info update {}: {}", ephSeqVersion, si);
        return;
      }
      this.amPluginendpointVersion = ephSeqVersion;
      this.amPluginInfo = info;
      if (info != null) {
        // Only update someone waiting for info if we have the info.
        if (amRegistryFuture != null) {
          amRegistryFuture.set(this);
          amRegistryFuture = null;
        }
        if (timeoutTimer != null) {
          timeoutTimer.cancel(true);
          timeoutTimer = null;
        }
      }
    }
  }

  @Override
  public AmPluginInfo getAmPluginInfo(Ref<Integer> version) {
    synchronized (amPluginInfoLock) {
      version.value = amPluginendpointVersion;
      return amPluginInfo;
    }
  }

  void setPoolName(String poolName) {
    this.poolName = poolName;
  }

  String getPoolName() {
    return poolName;
  }

  void setClusterFraction(double fraction) {
    this.clusterFraction = fraction;
  }

  void clearWm() {
    this.poolName = null;
    this.clusterFraction = 0f;
    this.queryId = null;
  }

  double getClusterFraction() {
    return this.clusterFraction;
  }

  boolean setSendingGuaranteed(int intAlloc) {
    assert intAlloc >= 0;
    synchronized (actualState) {
      actualState.target = intAlloc;
      if (actualState.sending != -1) return false; // The sender will take care of this.
      if (actualState.sent == intAlloc) return false; // The value didn't change.
      actualState.sending = intAlloc;
      return true;
    }
  }

  int setSentGuaranteed() {
    // Only one send can be active at the same time.
    synchronized (actualState) {
      assert actualState.sending != -1;
      actualState.sent = actualState.sending;
      actualState.sending = -1;
      return (actualState.sent == actualState.target) ? -1 : actualState.target;
    }
  }

  boolean setFailedToSendGuaranteed() {
    synchronized (actualState) {
      assert actualState.sending != -1;
      actualState.sending = -1;
      // It's ok to skip a failed message if the target has changed back to the old value.
      return (actualState.sent == actualState.target);
    }
  }

  public void handleUpdateError(int endpointVersion) {
    wmParent.addUpdateError(this, endpointVersion);
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(Object obj) {
    return obj == this;
  }

  boolean isIrrelevantForWm() {
    return killReason != null;
  }

  String getReasonForKill() {
    return killReason;
  }

  void setIsIrrelevantForWm(String killReason) {
    if (killReason == null) {
      throw new AssertionError("Cannot reset the kill reason " + this.killReason);
    }
    this.killReason = killReason;
  }

  private final class TimeoutRunnable implements Runnable {
    @Override
    public void run() {
      synchronized (amPluginInfoLock) {
        timeoutTimer = null;
        if (amRegistryFuture == null || amRegistryFuture.isDone()) return;
        amRegistryFuture.cancel(true);
        amRegistryFuture = null;
      }
    }
  }

  public void setQueryId(String queryId) {
    this.queryId = queryId;
  }

  public String getQueryId() {
    return this.queryId;
  }

  @Override
  public String toString() {
    return super.toString() +  ", WM state poolName=" + poolName + ", clusterFraction="
        + clusterFraction + ", queryId=" + queryId + ", killReason=" + killReason;
  }

}
