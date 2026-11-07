package com.einsteinmods.schematicvault.client.screen;

import com.einsteinmods.schematicvault.client.net.FailureKind;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where one file stands in this session.
 *
 * @param saved the saved file, for {@link Status#DONE}
 * @param failure why it failed, for {@link Status#FAILED}
 */
record DownloadState(Status status, @Nullable Path saved, @Nullable FailureKind failure) {
  static final DownloadState IDLE = new DownloadState(Status.IDLE, null, null);
  static final DownloadState IN_PROGRESS = new DownloadState(Status.IN_PROGRESS, null, null);

  enum Status {
    IDLE,
    IN_PROGRESS,
    DONE,
    FAILED
  }

  static DownloadState done(Path saved) {
    return new DownloadState(Status.DONE, saved, null);
  }

  static DownloadState failed(FailureKind failure) {
    return new DownloadState(Status.FAILED, null, failure);
  }
}
