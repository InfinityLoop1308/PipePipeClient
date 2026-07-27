function createSabrPolicy(sabr) {
  function text(bytes) {
    var result = '', index = 0, first, second, third, code;
    while (index < bytes.length) {
      first = bytes[index++];
      if (first < 128) {
        result += String.fromCharCode(first);
      } else if (first < 224) {
        second = bytes[index++];
        result += String.fromCharCode(((first & 31) << 6) | (second & 63));
      } else if (first < 240) {
        second = bytes[index++];
        third = bytes[index++];
        result += String.fromCharCode(((first & 15) << 12)
          | ((second & 63) << 6) | (third & 63));
      } else {
        second = bytes[index++];
        third = bytes[index++];
        code = ((first & 7) << 18) | ((second & 63) << 12)
          | ((third & 63) << 6) | (bytes[index++] & 63);
        code -= 65536;
        result += String.fromCharCode(55296 + (code >> 10), 56320 + (code & 1023));
      }
    }
    return result;
  }

  function mediaHeader(event) {
    var fields = sabr.proto.decode(sabr.base64.decode(event.data));
    var result = {};
    var timeRange = null;
    var index, field, nested, nestedIndex, nestedField;
    for (index = 0; index < fields.length; index++) {
      field = fields[index];
      if (field.n === 1) result.headerId = field.v;
      else if (field.n === 2) result.videoId = text(field.b);
      else if (field.n === 3) result.itag = field.v;
      else if (field.n === 4) result.lastModified = field.v;
      else if (field.n === 5) result.xtags = text(field.b);
      else if (field.n === 6) result.startRange = field.v;
      else if (field.n === 7) result.compressionAlgorithm = field.v;
      else if (field.n === 8) result.initSegment = field.v !== 0;
      else if (field.n === 9) result.sequenceNumber = field.v;
      else if (field.n === 10) result.bitrateBps = field.v;
      else if (field.n === 11) result.startMs = field.v;
      else if (field.n === 12) result.durationMs = field.v;
      else if (field.n === 13) {
        nested = sabr.proto.decode(field.b);
        for (nestedIndex = 0; nestedIndex < nested.length; nestedIndex++) {
          nestedField = nested[nestedIndex];
          if (nestedField.n === 1 && result.itag === undefined) result.itag = nestedField.v;
          else if (nestedField.n === 2 && result.lastModified === undefined) {
            result.lastModified = nestedField.v;
          } else if (nestedField.n === 3 && result.xtags === undefined) {
            result.xtags = text(nestedField.b);
          }
        }
      } else if (field.n === 14) result.contentLength = field.v;
      else if (field.n === 15) {
        timeRange = {};
        nested = sabr.proto.decode(field.b);
        for (nestedIndex = 0; nestedIndex < nested.length; nestedIndex++) {
          nestedField = nested[nestedIndex];
          if (nestedField.n === 1) timeRange.start = nestedField.v;
          else if (nestedField.n === 2) timeRange.duration = nestedField.v;
          else if (nestedField.n === 3) timeRange.timescale = nestedField.v;
        }
      } else if (field.n === 16) result.sequenceLastModified = field.v;
    }
    if (timeRange) {
      result.timeRangeStartTicks = timeRange.start;
      result.timeRangeDurationTicks = timeRange.duration;
      result.timeRangeTimescale = timeRange.timescale;
      if (timeRange.timescale > 0) {
        if (result.startMs === undefined && timeRange.start >= 0) {
          result.startMs = Math.floor(timeRange.start * 1000 / timeRange.timescale);
        }
        if (result.durationMs === undefined && timeRange.duration >= 0) {
          result.durationMs = Math.floor(timeRange.duration * 1000 / timeRange.timescale);
        }
      }
    }
    return result;
  }

  function response(event) {
    var actions = ['APPLY_BUILTIN_RESPONSE_STATE'];
    var state = {
      redirectCount: event.redirectCount,
      poTokenRefreshes: event.poTokenRefreshes
    };
    var redirect = event.builtin.redirectUrl;
    var backoff = Math.max(0, event.builtin.backoffMs || 0);
    if (redirect) {
      actions.push('APPLY_REDIRECT');
      state.redirectCount++;
    }
    if (event.builtin.error) {
      actions.push('FAIL_SABR_ERROR');
      return {actions: actions, state: state, redirectUrl: redirect,
        errorDetails: 'SABR error'};
    }
    if (event.builtin.reload) {
      actions.push('TRY_RELOAD');
      return {actions: actions, state: state, redirectUrl: redirect};
    }
    if (event.builtin.protection) {
      actions.push(event.mode === 'FETCH_SEGMENT' ? 'REQUIRE_PO_TOKEN' : 'REFRESH_PO_TOKEN');
    }
    if (event.mode === 'PUMP' && event.segmentCount > 0) {
      state.redirectCount = 0;
      state.poTokenRefreshes = 0;
      actions.push('RESET_RECOVERY_BUDGETS');
    }
    if (backoff > 0) {
      actions.push(event.honorBackoff ? 'SLEEP_BACKOFF' : 'DEFER_BACKOFF');
    } else if (!event.honorBackoff) {
      actions.push('CLEAR_DEMAND_BACKOFF');
    }
    actions.push(event.mode === 'FETCH_SEGMENT' && event.builtin.protection
      ? 'RETRY' : 'CONTINUE');
    return {actions: actions, state: state, backoffMs: backoff, redirectUrl: redirect};
  }

  function demandRoute(event) {
    var recover = event.responsesWithoutDemandedSegment > event.recoveryCount;
    if (event.targetStartMs < event.bufferedEdgeMs) {
      return {route: recover ? 'RECOVER_REWIND' : 'REWIND'};
    }
    if (event.targetStartMs > event.bufferedEdgeMs + 30000) {
      return {route: recover ? 'RECOVER_FORWARD' : 'FORWARD'};
    }
    return {route: recover ? 'RECOVER_MISSING' : 'STREAM'};
  }

  function demandResponse(event) {
    if (event.responsesWithoutDemandedSegment >= 3) {
      return {outcome: 'FAIL_REPEATED_TARGET_OMISSION', retryDelayMs: 0};
    }
    if (event.elapsedMs >= 15000) {
      return {outcome: event.targetTrackSegmentCount > 0
        ? 'FAIL_REPEATED_TARGET_OMISSION' : 'FAIL_NO_TARGET_MEDIA', retryDelayMs: 0};
    }
    return {outcome: 'CONTINUE', retryDelayMs: 0};
  }

  return {
    describe: function () {
      return {demand: true, media: {headerType: 20, mediaType: 21, endType: 22,
        headerDecoder: 'builtin'}};
    },
    initialRequest: function (event) { return {body: event.fallbackBody}; },
    followUpRequest: function (event) { return {body: event.fallbackBody}; },
    response: response,
    demandRoute: demandRoute,
    demandResponse: demandResponse,
    mediaHeader: mediaHeader
  };
}
