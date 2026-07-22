package org.schabi.newpipe.player.datasource;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.sabr.SabrCompatibilityProfileDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Atomic two-generation profile cache plus an independent monotonic revision floor. */
final class SabrProfileCache {
    private static final int STATE_MAGIC = 0x53435043;
    private static final int FLOOR_MAGIC = 0x53435052;
    private static final int STATE_VERSION = 2;
    private static final int FLOOR_VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES =
            SabrCompatibilityProfileDocument.MAX_DOCUMENT_BYTES;
    private static final int MAX_STATE_BYTES = 2 * MAX_DOCUMENT_BYTES + 32;

    @NonNull private final AtomicFile stateFile;
    @NonNull private final AtomicFile floorFile;

    SabrProfileCache(@NonNull final Context context, @NonNull final String channel) {
        if (!channel.matches("[a-z0-9-]{1,24}")) {
            throw new IllegalArgumentException("Invalid SABR profile channel");
        }
        final File directory = context.getApplicationContext().getFilesDir();
        stateFile = new AtomicFile(new File(directory,
                "sabr-compatibility-" + channel + ".bin"));
        floorFile = new AtomicFile(new File(directory,
                "sabr-compatibility-" + channel + ".rev"));
    }

    @NonNull
    State readState() throws IOException {
        if (stateFile.getBaseFile().length() > MAX_STATE_BYTES) {
            throw new IOException("SABR profile cache exceeds size limit");
        }
        final DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(stateFile.readFully()));
        if (input.readInt() != STATE_MAGIC) {
            throw new IOException("Unsupported SABR profile cache");
        }
        final int version = input.readUnsignedByte();
        if (version != 1 && version != STATE_VERSION) {
            throw new IOException("Unsupported SABR profile cache");
        }
        final byte[] active = readDocument(input);
        final byte[] previous = readDocument(input);
        final long fallbackFromRevision = version == STATE_VERSION ? input.readLong() : 0;
        if (input.available() != 0) {
            throw new IOException("Invalid SABR profile cache state");
        }
        return new State(active, previous, fallbackFromRevision);
    }

    long readRevisionFloor() {
        try {
            final DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(floorFile.readFully()));
            if (input.readInt() != FLOOR_MAGIC || input.readUnsignedByte() != FLOOR_VERSION) {
                return 0;
            }
            final long revision = input.readLong();
            return revision >= 0 && input.available() == 0 ? revision : 0;
        } catch (final IOException ignored) {
            return 0;
        }
    }

    void writeState(@NonNull final State state) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(STATE_MAGIC);
        output.writeByte(STATE_VERSION);
        writeDocument(output, state.active);
        writeDocument(output, state.previous);
        output.writeLong(state.fallbackFromRevision);
        output.flush();
        write(stateFile, bytes.toByteArray());
    }

    void writeRevisionFloor(final long revision) throws IOException {
        if (revision < 0) {
            throw new IllegalArgumentException("Negative SABR profile revision floor");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(FLOOR_MAGIC);
        output.writeByte(FLOOR_VERSION);
        output.writeLong(revision);
        output.flush();
        write(floorFile, bytes.toByteArray());
    }

    void deleteState() {
        stateFile.delete();
    }

    private static void writeDocument(@NonNull final DataOutputStream output,
                                      @Nullable final byte[] document) throws IOException {
        if (document == null) {
            output.writeInt(-1);
            return;
        }
        validateDocumentLength(document.length);
        output.writeInt(document.length);
        output.write(document);
    }

    @Nullable
    private static byte[] readDocument(@NonNull final DataInputStream input) throws IOException {
        final int length = input.readInt();
        if (length == -1) {
            return null;
        }
        validateDocumentLength(length);
        final byte[] document = new byte[length];
        input.readFully(document);
        return document;
    }

    private static void validateDocumentLength(final int length) {
        if (length <= 0 || length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Invalid cached SABR profile size");
        }
    }

    private static void write(@NonNull final AtomicFile file, @NonNull final byte[] value)
            throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(value);
            output.flush();
            file.finishWrite(output);
        } catch (final IOException failure) {
            if (output != null) {
                file.failWrite(output);
            }
            throw failure;
        }
    }

    static final class State {
        @Nullable final byte[] active;
        @Nullable final byte[] previous;
        final long fallbackFromRevision;

        State(@Nullable final byte[] active,
              @Nullable final byte[] previous,
              final long fallbackFromRevision) {
            if (fallbackFromRevision < 0 || active == null
                    && (previous != null || fallbackFromRevision != 0)
                    || fallbackFromRevision != 0 && previous != null) {
                throw new IllegalArgumentException("Invalid SABR profile cache generations");
            }
            this.active = active == null ? null : active.clone();
            this.previous = previous == null ? null : previous.clone();
            this.fallbackFromRevision = fallbackFromRevision;
        }

        boolean isGenerationLayoutValid(final long revisionFloor) {
            return fallbackFromRevision == 0 || fallbackFromRevision == revisionFloor;
        }
    }
}
