/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.hls;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.hls.HlsMediaPlaylist.Segment;
import com.google.android.exoplayer.upstream.NetworkLoadable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.regex.Pattern;

/**
 * HLS playlists parsing logic.
 */
public final class HlsPlaylistParser implements NetworkLoadable.Parser<HlsPlaylist> {

  private static final String VERSION_TAG = "#EXT-X-VERSION";
  private static final String STREAM_INF_TAG = "#EXT-X-STREAM-INF";
  private static final String MEDIA_TAG = "#EXT-X-MEDIA";
  private static final String DISCONTINUITY_TAG = "#EXT-X-DISCONTINUITY";
  private static final String MEDIA_DURATION_TAG = "#EXTINF";
  private static final String MEDIA_SEQUENCE_TAG = "#EXT-X-MEDIA-SEQUENCE";
  private static final String TARGET_DURATION_TAG = "#EXT-X-TARGETDURATION";
  private static final String ENDLIST_TAG = "#EXT-X-ENDLIST";
  private static final String KEY_TAG = "#EXT-X-KEY";
  private static final String BYTERANGE_TAG = "#EXT-X-BYTERANGE";

  private static final String BANDWIDTH_ATTR = "BANDWIDTH";
  private static final String CODECS_ATTR = "CODECS";
  private static final String RESOLUTION_ATTR = "RESOLUTION";
  private static final String LANGUAGE_ATTR = "LANGUAGE";
  private static final String NAME_ATTR = "NAME";
  private static final String AUTOSELECT_ATTR = "AUTOSELECT";
  private static final String DEFAULT_ATTR = "DEFAULT";
  private static final String TYPE_ATTR = "TYPE";
  private static final String METHOD_ATTR = "METHOD";
  private static final String URI_ATTR = "URI";
  private static final String IV_ATTR = "IV";

  private static final String AUDIO_TYPE = "AUDIO";
  private static final String VIDEO_TYPE = "VIDEO";
  private static final String SUBTITLES_TYPE = "SUBTITLES";
  private static final String CLOSED_CAPTIONS_TYPE = "CLOSED-CAPTIONS";

  private static final Pattern BANDWIDTH_ATTR_REGEX =
      Pattern.compile(BANDWIDTH_ATTR + "=(\\d+)\\b");
  private static final Pattern CODECS_ATTR_REGEX =
      Pattern.compile(CODECS_ATTR + "=\"(.+?)\"");
  private static final Pattern RESOLUTION_ATTR_REGEX =
      Pattern.compile(RESOLUTION_ATTR + "=(\\d+x\\d+)");
  private static final Pattern MEDIA_DURATION_REGEX =
      Pattern.compile(MEDIA_DURATION_TAG + ":([\\d.]+),");
  private static final Pattern MEDIA_SEQUENCE_REGEX =
      Pattern.compile(MEDIA_SEQUENCE_TAG + ":(\\d+)\\b");
  private static final Pattern TARGET_DURATION_REGEX =
      Pattern.compile(TARGET_DURATION_TAG + ":(\\d+)\\b");
  private static final Pattern VERSION_REGEX =
      Pattern.compile(VERSION_TAG + ":(\\d+)\\b");
  private static final Pattern BYTERANGE_REGEX =
      Pattern.compile(BYTERANGE_TAG + ":(\\d+(?:@\\d+)?)\\b");
  private static final Pattern METHOD_ATTR_REGEX =
      Pattern.compile(METHOD_ATTR + "=([^,.*]+)");
  private static final Pattern URI_ATTR_REGEX =
      Pattern.compile(URI_ATTR + "=\"(.+)\"");
  private static final Pattern IV_ATTR_REGEX =
      Pattern.compile(IV_ATTR + "=([^,.*]+)");
  private static final Pattern TYPE_ATTR_REGEX =
      Pattern.compile(TYPE_ATTR + "=(" + AUDIO_TYPE + "|" + VIDEO_TYPE + "|" + SUBTITLES_TYPE + "|"
          + CLOSED_CAPTIONS_TYPE + ")");
  private static final Pattern LANGUAGE_ATTR_REGEX =
      Pattern.compile(LANGUAGE_ATTR + "=\"(.+?)\"");
  private static final Pattern NAME_ATTR_REGEX =
      Pattern.compile(NAME_ATTR + "=\"(.+?)\"");
  private static final Pattern AUTOSELECT_ATTR_REGEX =
      Pattern.compile(AUTOSELECT_ATTR + "=\"(.+?)\"");
  private static final Pattern DEFAULT_ATTR_REGEX =
      Pattern.compile(DEFAULT_ATTR + "=\"(.+?)\"");

  @Override
  public HlsPlaylist parse(String connectionUrl, InputStream inputStream)
      throws IOException, ParserException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    Queue<String> extraLines = new LinkedList<String>();
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          // Do nothing.
        } else if (line.startsWith(STREAM_INF_TAG)) {
          extraLines.add(line);
          return parseMasterPlaylist(new LineIterator(extraLines, reader), connectionUrl);
        } else if (line.startsWith(TARGET_DURATION_TAG)
            || line.startsWith(MEDIA_SEQUENCE_TAG)
            || line.startsWith(MEDIA_DURATION_TAG)
            || line.startsWith(KEY_TAG)
            || line.startsWith(BYTERANGE_TAG)
            || line.equals(DISCONTINUITY_TAG)
            || line.equals(ENDLIST_TAG)) {
          extraLines.add(line);
          return parseMediaPlaylist(new LineIterator(extraLines, reader), connectionUrl);
        } else {
          extraLines.add(line);
        }
      }
    } finally {
      reader.close();
    }
    throw new ParserException("Failed to parse the playlist, could not identify any tags.");
  }

  private static HlsMasterPlaylist parseMasterPlaylist(LineIterator iterator, String baseUri)
      throws IOException {
    ArrayList<Variant> variants = new ArrayList<Variant>();
    ArrayList<Subtitle> subtitles = new ArrayList<Subtitle>();
    int bandwidth = 0;
    String[] codecs = null;
    int width = -1;
    int height = -1;
    int variantIndex = 0;

    boolean expectingStreamInfUrl = false;
    String line;
    while (iterator.hasNext()) {
      line = iterator.next();
      if (line.startsWith(MEDIA_TAG)) {
        String type = HlsParserUtil.parseStringAttr(line, TYPE_ATTR_REGEX, TYPE_ATTR);
        if (SUBTITLES_TYPE.equals(type)) {
          // We assume all subtitles belong to the same group.
          String name = HlsParserUtil.parseStringAttr(line, NAME_ATTR_REGEX, NAME_ATTR);
          String uri = HlsParserUtil.parseStringAttr(line, URI_ATTR_REGEX, URI_ATTR);
          String language = HlsParserUtil.parseOptionalStringAttr(line, LANGUAGE_ATTR_REGEX);
          boolean isDefault = HlsParserUtil.parseOptionalBoolAttr(line, DEFAULT_ATTR_REGEX);
          boolean autoSelect = HlsParserUtil.parseOptionalBoolAttr(line, AUTOSELECT_ATTR_REGEX);
          subtitles.add(new Subtitle(name, uri, language, isDefault, autoSelect));
        } else {
          // TODO: Support other types of media tag.
        }
      } else if (line.startsWith(STREAM_INF_TAG)) {
        bandwidth = HlsParserUtil.parseIntAttr(line, BANDWIDTH_ATTR_REGEX, BANDWIDTH_ATTR);
        String codecsString = HlsParserUtil.parseOptionalStringAttr(line, CODECS_ATTR_REGEX);
        if (codecsString != null) {
          codecs = codecsString.split("(\\s*,\\s*)|(\\s*$)");
        } else {
          codecs = null;
        }
        String resolutionString = HlsParserUtil.parseOptionalStringAttr(line,
            RESOLUTION_ATTR_REGEX);
        if (resolutionString != null) {
          String[] widthAndHeight = resolutionString.split("x");
          width = Integer.parseInt(widthAndHeight[0]);
          height = Integer.parseInt(widthAndHeight[1]);
        } else {
          width = -1;
          height = -1;
        }
        expectingStreamInfUrl = true;
      } else if (!line.startsWith("#") && expectingStreamInfUrl) {
        variants.add(new Variant(variantIndex++, line, bandwidth, codecs, width, height));
        bandwidth = 0;
        codecs = null;
        width = -1;
        height = -1;
        expectingStreamInfUrl = false;
      }
    }
    return new HlsMasterPlaylist(baseUri, Collections.unmodifiableList(variants),
        Collections.unmodifiableList(subtitles));
  }

  private static HlsMediaPlaylist parseMediaPlaylist(LineIterator iterator, String baseUri)
      throws IOException {
    int mediaSequence = 0;
    int targetDurationSecs = 0;
    int version = 1; // Default version == 1.
    boolean live = true;
    List<Segment> segments = new ArrayList<Segment>();

    double segmentDurationSecs = 0.0;
    boolean segmentDiscontinuity = false;
    long segmentStartTimeUs = 0;
    String segmentEncryptionMethod = null;
    String segmentEncryptionKeyUri = null;
    String segmentEncryptionIV = null;
    int segmentByterangeOffset = 0;
    int segmentByterangeLength = C.LENGTH_UNBOUNDED;

    int segmentMediaSequence = 0;

    String line;
    while (iterator.hasNext()) {
      line = iterator.next();
      if (line.startsWith(TARGET_DURATION_TAG)) {
        targetDurationSecs = HlsParserUtil.parseIntAttr(line, TARGET_DURATION_REGEX,
            TARGET_DURATION_TAG);
      } else if (line.startsWith(MEDIA_SEQUENCE_TAG)) {
        mediaSequence = HlsParserUtil.parseIntAttr(line, MEDIA_SEQUENCE_REGEX, MEDIA_SEQUENCE_TAG);
        segmentMediaSequence = mediaSequence;
      } else if (line.startsWith(VERSION_TAG)) {
        version = HlsParserUtil.parseIntAttr(line, VERSION_REGEX, VERSION_TAG);
      } else if (line.startsWith(MEDIA_DURATION_TAG)) {
        segmentDurationSecs = HlsParserUtil.parseDoubleAttr(line, MEDIA_DURATION_REGEX,
            MEDIA_DURATION_TAG);
      } else if (line.startsWith(KEY_TAG)) {
        segmentEncryptionMethod = HlsParserUtil.parseStringAttr(line, METHOD_ATTR_REGEX,
            METHOD_ATTR);
        if (segmentEncryptionMethod.equals(HlsMediaPlaylist.ENCRYPTION_METHOD_NONE)) {
          segmentEncryptionKeyUri = null;
          segmentEncryptionIV = null;
        } else {
          segmentEncryptionKeyUri = HlsParserUtil.parseStringAttr(line, URI_ATTR_REGEX,
              URI_ATTR);
          segmentEncryptionIV = HlsParserUtil.parseOptionalStringAttr(line, IV_ATTR_REGEX);
          if (segmentEncryptionIV == null) {
            segmentEncryptionIV = Integer.toHexString(segmentMediaSequence);
          }
        }
      } else if (line.startsWith(BYTERANGE_TAG)) {
        String byteRange = HlsParserUtil.parseStringAttr(line, BYTERANGE_REGEX, BYTERANGE_TAG);
        String[] splitByteRange = byteRange.split("@");
        segmentByterangeLength = Integer.parseInt(splitByteRange[0]);
        if (splitByteRange.length > 1) {
          segmentByterangeOffset = Integer.parseInt(splitByteRange[1]);
        }
      } else if (line.equals(DISCONTINUITY_TAG)) {
        segmentDiscontinuity = true;
      } else if (!line.startsWith("#")) {
        segmentMediaSequence++;
        if (segmentByterangeLength == C.LENGTH_UNBOUNDED) {
          segmentByterangeOffset = 0;
        }
        segments.add(new Segment(line, segmentDurationSecs, segmentDiscontinuity,
            segmentStartTimeUs, segmentEncryptionMethod, segmentEncryptionKeyUri,
            segmentEncryptionIV, segmentByterangeOffset, segmentByterangeLength));
        segmentStartTimeUs += (long) (segmentDurationSecs * C.MICROS_PER_SECOND);
        segmentDiscontinuity = false;
        segmentDurationSecs = 0.0;
        if (segmentByterangeLength != C.LENGTH_UNBOUNDED) {
          segmentByterangeOffset += segmentByterangeLength;
        }
        segmentByterangeLength = C.LENGTH_UNBOUNDED;
      } else if (line.equals(ENDLIST_TAG)) {
        live = false;
        break;
      }
    }
    return new HlsMediaPlaylist(baseUri, mediaSequence, targetDurationSecs, version, live,
        Collections.unmodifiableList(segments));
  }

  private static class LineIterator {

    private final BufferedReader reader;
    private final Queue<String> extraLines;

    private String next;

    public LineIterator(Queue<String> extraLines, BufferedReader reader) {
      this.extraLines = extraLines;
      this.reader = reader;
    }

    public boolean hasNext() throws IOException {
      if (next != null) {
        return true;
      }
      if (!extraLines.isEmpty()) {
        next = extraLines.poll();
        return true;
      }
      while ((next = reader.readLine()) != null) {
        next = next.trim();
        if (!next.isEmpty()) {
          return true;
        }
      }
      return false;
    }

    public String next() throws IOException {
      String result = null;
      if (hasNext()) {
        result = next;
        next = null;
      }
      return result;
    }

  }

}
