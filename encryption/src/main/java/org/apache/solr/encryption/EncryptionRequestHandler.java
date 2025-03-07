/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.encryption;

import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.update.CommitUpdateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import static org.apache.solr.encryption.CommitUtil.readLatestCommit;
import static org.apache.solr.encryption.EncryptionUtil.*;

/**
 * Handles an encryption request for a specific {@link SolrCore}.
 * <p>
 * The caller provides the mandatory {@link #PARAM_KEY_ID} request parameter to define the encryption
 * key id to use to encrypt the index files. To decrypt the index to cleartext, the special parameter
 * value {@link #NO_KEY_ID} must be provided.
 * <p>
 * The encryption processing is asynchronous. The request returns immediately with two response
 * parameters. {@link #ENCRYPTION_STATE} parameter with values {@link #STATE_PENDING},
 * {@link #STATE_COMPLETE}, or {@link #STATE_BUSY}. And {@link #STATUS} parameter with values
 * {@link #STATUS_SUCCESS} or {@link #STATUS_FAILURE}.
 * <p>
 * The expected usage of this handler is to first send an encryption request with a key id, and
 * receive a response with {@link #STATUS_SUCCESS} and a {@link #STATE_PENDING}. If the caller needs
 * to know when the encryption is complete, it can (optionally) repeatedly send the same encryption
 * request with the same key id, until it receives a response with {@link #STATUS_SUCCESS} and a
 * {@link #STATE_COMPLETE}.
 * <p>
 * If the handler returns a response with {@link #STATE_BUSY}, it means that another encryption for a
 * different key id is ongoing on the same Solr core. It cannot start a new encryption until it finishes.
 * <p>
 * If the handler returns a response with {@link #STATUS_FAILURE}, it means the request did not succeed
 * and should be retried by the caller (there should be error logs).
 */
public class EncryptionRequestHandler extends RequestHandlerBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /**
   * Key id request parameter.
   * Its value should be {@link #NO_KEY_ID} for no key.
   */
  public static final String PARAM_KEY_ID = "encryptionKeyId";
  /**
   * Value of the key id parameter meaning there is no key and no encryption.
   */
  public static final String NO_KEY_ID = "no_key_id";

  /**
   * Encryption pending boolean parameter, in the commit user data.
   * When present and true, this means the encryption process is still pending.
   * It includes the {@link EncryptionUpdateHandler#TRANSFERABLE_COMMIT_DATA} prefix to be transferred from a
   * commit to the next one automatically.
   */
  private static final String COMMIT_ENCRYPTION_PENDING = COMMIT_CRYPTO + "encryptionPending";

  /**
   * Status of the request.
   */
  public static final String STATUS = "status";
  /**
   * One of {@link #STATUS} values: the request was processed successfully. Get additional information
   * with the {@link #ENCRYPTION_STATE} response parameter.
   */
  public static final String STATUS_SUCCESS = "success";
  /**
   * One of {@link #STATUS} values: the request was not processed correctly, an error occurred.
   */
  public static final String STATUS_FAILURE = "failure";

  /**
   * Response parameter name to provide the status of the encryption.
   */
  public static final String ENCRYPTION_STATE = "encryptionState";
  /**
   * One of {@link #ENCRYPTION_STATE} values: the encryption with the provided key id is ongoing and pending.
   */
  public static final String STATE_PENDING = "pending";
  /**
   * One of {@link #ENCRYPTION_STATE} values: the encryption with the provided key id is complete.
   */
  public static final String STATE_COMPLETE = "complete";
  /**
   * One of {@link #ENCRYPTION_STATE} values: another encryption for a different key id is ongoing
   * on the same Solr core; cannot start a new encryption until it finishes.
   */
  public static final String STATE_BUSY = "busy";

  private static final Object pendingEncryptionLock = new Object();
  private static final Map<String, PendingKeyId> pendingEncryptions = new HashMap<>();

  private final ExecutorService executor = ExecutorUtil.newMDCAwareFixedThreadPool(4, new ThreadFactory() {
    private int threadNum;

    @Override
    public synchronized Thread newThread(Runnable r) {
      Thread t = new Thread(r, "Encryption-" + threadNum++);
      t.setDaemon(true);
      return t;
    }
  });

  @Override
  public void close() throws IOException {
    try {
      ExecutorUtil.shutdownAndAwaitTermination(executor);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      super.close();
    }
  }

  @Override
  public String getDescription() {
    return "Handles encryption requests";
  }

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    return PermissionNameProvider.Name.UPDATE_PERM;
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    long startTimeMs = System.currentTimeMillis();
    String keyId = req.getParams().get(PARAM_KEY_ID);
    if (keyId == null || keyId.isEmpty()) {
      rsp.add(STATUS, STATUS_FAILURE);
      throw new IOException("Parameter " + PARAM_KEY_ID + " must be present and not empty."
                              + " Use [" + PARAM_KEY_ID + "=\"" + NO_KEY_ID + "\"] for explicit decryption.");
    } else if (keyId.equals(NO_KEY_ID)) {
      keyId = null;
    }
    boolean success = false;
    String encryptionState = STATE_PENDING;
    try {
      SegmentInfos segmentInfos = readLatestCommit(req.getCore());
      if (segmentInfos.size() == 0) {
        commitEmptyIndexForEncryption(keyId, segmentInfos, req);
        encryptionState = STATE_COMPLETE;
        success = true;
        return;
      }

      boolean encryptionComplete = false;
      if (isCommitActiveKeyId(keyId, segmentInfos)) {
        log.debug("provided keyId={} is the current active key id", keyId);
        if (Boolean.parseBoolean(segmentInfos.getUserData().get(COMMIT_ENCRYPTION_PENDING))) {
          encryptionComplete = areAllSegmentsEncryptedWithKeyId(keyId, req.getCore(), segmentInfos);
          if (encryptionComplete) {
            commitEncryptionComplete(keyId, segmentInfos, req);
          }
        } else {
          encryptionComplete = true;
        }
      }
      if (encryptionComplete) {
        encryptionState = STATE_COMPLETE;
        success = true;
        return;
      }

      synchronized (pendingEncryptionLock) {
        PendingKeyId pendingKeyId = pendingEncryptions.get(req.getCore().getName());
        if (pendingKeyId != null) {
          if (Objects.equals(pendingKeyId.keyId, keyId)) {
            log.debug("ongoing encryption for keyId={}", keyId);
            encryptionState = STATE_PENDING;
            success = true;
          } else {
            log.debug("core busy encrypting for keyId={} different than requested keyId={}", pendingKeyId.keyId, keyId);
            encryptionState = STATE_BUSY;
          }
          return;
        }
        pendingEncryptions.put(req.getCore().getName(), new PendingKeyId(keyId));
      }
      try {
        commitEncryptionStart(keyId, segmentInfos, req);
        encryptAsync(req, startTimeMs);
        success = true;
      } finally {
        if (!success) {
          synchronized (pendingEncryptionLock) {
            pendingEncryptions.remove(req.getCore().getName());
          }
        }
      }

    } finally {
      if (success) {
        rsp.add(STATUS, STATUS_SUCCESS);
      } else {
        rsp.add(STATUS, STATUS_FAILURE);
      }
      log.debug("responding encryption state={} success={} for keyId={}", encryptionState, success, keyId);
      rsp.add(ENCRYPTION_STATE, encryptionState);
    }
  }

  private void commitEmptyIndexForEncryption(@Nullable String keyId,
                                             SegmentInfos segmentInfos,
                                             SolrQueryRequest req) throws IOException {
    // Commit no change, with the new active key id in the commit user data.
    log.debug("commit on empty index for keyId={}", keyId);
    CommitUpdateCommand commitCmd = new CommitUpdateCommand(req, false);
    commitCmd.commitData = new HashMap<>(segmentInfos.getUserData());
    commitCmd.commitData.remove(COMMIT_ENCRYPTION_PENDING);
    setNewActiveKeyIdInCommit(keyId, commitCmd, req);
    assert !commitCmd.commitData.isEmpty();
    req.getCore().getUpdateHandler().commit(commitCmd);
  }

  private void setNewActiveKeyIdInCommit(String keyId, CommitUpdateCommand commitCmd, SolrQueryRequest req)
    throws IOException {
    if (keyId == null) {
      removeActiveKeyRefFromCommit(commitCmd.commitData);
      ensureNonEmptyCommitDataForEmptyCommit(commitCmd.commitData);
    } else {
      byte[] keyCookie = getKeyManager(req).getKeyCookie(keyId);
      EncryptionUtil.setNewActiveKeyIdInCommit(keyId, keyCookie, commitCmd.commitData);
    }
  }

  private KeyManager getKeyManager(SolrQueryRequest req) {
    try {
      return ((EncryptionDirectoryFactory) req.getCore().getDirectoryFactory()).getKeyManager();
    } catch (ClassCastException e) {
      throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE,
                              "DirectoryFactory class must be set to " + EncryptionDirectoryFactory.class.getName() + " to use " + getClass().getSimpleName(),
                              e);
    }
  }

  private void commitEncryptionComplete(String keyId,
                                        SegmentInfos segmentInfos,
                                        SolrQueryRequest req) throws IOException {
    assert isCommitActiveKeyId(keyId, segmentInfos);
    log.debug("commit encryption complete for keyId={}", keyId);
    CommitUpdateCommand commitCmd = new CommitUpdateCommand(req, false);
    commitCmd.commitData = new HashMap<>(segmentInfos.getUserData());
    commitCmd.commitData.remove(COMMIT_ENCRYPTION_PENDING);
    // All segments are encrypted with the key id,
    // clear the oldest inactive key ids from the commit user data.
    clearOldInactiveKeyIdsFromCommit(commitCmd.commitData);
    ensureNonEmptyCommitDataForEmptyCommit(commitCmd.commitData);
    req.getCore().getUpdateHandler().commit(commitCmd);
  }

  private void ensureNonEmptyCommitDataForEmptyCommit(Map<String, String> commitData) {
    if (commitData.isEmpty()) {
      // Ensure that there is some data in the commit user data so that an empty commit
      // (with no change) is allowed.
      commitData.put("crypto.cleartext", "true");
    }
  }

  private void commitEncryptionStart(String keyId,
                                     SegmentInfos segmentInfos,
                                     SolrQueryRequest req) throws IOException {
    log.debug("commit encryption starting for keyId={}", keyId);
    CommitUpdateCommand commitCmd = new CommitUpdateCommand(req, false);
    commitCmd.commitData = new HashMap<>(segmentInfos.getUserData());
    commitCmd.commitData.put(COMMIT_ENCRYPTION_PENDING, "true");
    setNewActiveKeyIdInCommit(keyId, commitCmd, req);
    req.getCore().getUpdateHandler().commit(commitCmd);
  }

  private void encryptAsync(SolrQueryRequest req, long startTimeMs) {
    log.debug("submitting async encryption");
    executor.submit(() -> {
      try {
        log.debug("running async encryption");
        CommitUpdateCommand commitCmd = new CommitUpdateCommand(req, true);
        // Trigger EncryptionMergePolicy.findForcedMerges() to re-encrypt
        // each segment which is not encrypted with the latest active key id.
        // TODO: Set maxOptimizeSegments to Integer.MAX_VALUE to trigger EncryptionMergePolicy
        //  when EncryptionHeavyLoadTest passes with it.
        commitCmd.maxOptimizeSegments = 1;
        req.getCore().getUpdateHandler().commit(commitCmd);
        log.info("Successfully encrypted the index in " + elapsedTime(startTimeMs));
      } catch (IOException e) {
        log.error("Exception while encrypting the index after " + elapsedTime(startTimeMs), e);
      } finally {
        synchronized (pendingEncryptionLock) {
          pendingEncryptions.remove(req.getCore().getName());
        }
      }
      return null;
    });
  }

  public static boolean areAllSegmentsEncryptedWithKeyId(@Nullable String keyId,
                                                         SolrCore core,
                                                         SegmentInfos segmentInfos) throws IOException {
    DirectoryFactory directoryFactory = core.getDirectoryFactory();
    Directory indexDir = directoryFactory.get(core.getIndexDir(),
                                              DirectoryFactory.DirContext.DEFAULT,
                                              DirectoryFactory.LOCK_TYPE_NONE);
    try {
      EncryptionDirectory dir = (EncryptionDirectory) indexDir;
      List<SegmentCommitInfo> segmentsWithOldKeyId = dir.getSegmentsWithOldKeyId(segmentInfos, keyId);
      log.debug("encryption is pending; {} segments do not have keyId={}", segmentsWithOldKeyId.size(), keyId);
      return segmentsWithOldKeyId.isEmpty();
    } finally {
      directoryFactory.release(indexDir);
    }
  }

  private boolean isCommitActiveKeyId(String keyId, SegmentInfos segmentInfos) {
    String keyRef = getActiveKeyRefFromCommit(segmentInfos.getUserData());
    String activeKeyId = keyRef == null ? null : getKeyIdFromCommit(keyRef, segmentInfos.getUserData());
    return Objects.equals(keyId, activeKeyId);
  }

  private static String elapsedTime(long startTimeMs) {
    return (System.currentTimeMillis() - startTimeMs) + " ms";
  }

  /**
   * Wraps a nullable key id (null key id means cleartext).
   */
  private static class PendingKeyId {
    @Nullable
    final String keyId;

    PendingKeyId(@Nullable String keyId) {
      this.keyId = keyId;
    }
  }
}
