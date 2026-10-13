#ifndef CZLIB_H
#define CZLIB_H

#include <zlib.h>

/// `inflateInit2` is a macro and therefore not visible from Swift.
/// `windowBits` = 15 + 32 enables automatic gzip/zlib header detection.
int czlib_inflate_init(z_stream *stream, int windowBits);

#endif /* CZLIB_H */
