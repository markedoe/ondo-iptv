#include "CZlib.h"

int czlib_inflate_init(z_stream *stream, int windowBits) {
    return inflateInit2(stream, windowBits);
}
