import AVFoundation
import CoreMedia
import IPTVCore

/// What the player reads of a film's stream from its tracks (`MeasuredSpecs`, docs/VERSIONS.md): the picture's size,
/// range and codec, the sound's codec and channels, from AVFoundation's public format descriptions. Only what they say:
/// a transfer function they do not give stays unknown, and a 10-bit profile alone is no HDR. The same as Android's
/// `MeasuredTracks`.
extension PlayerController {
    /// A film's item is ready: what its stream is made of goes to `onSpecs`. Its own stream only (the first address, not
    /// a fallback's), AVFoundation's engine only, never a channel.
    func readSpecs(of playerItem: AVPlayerItem) async {
        guard let item, !item.isLive, item.reference.type == .movie, candidateIndex == 0, activeEngine == .apple else { return }
        let asset = playerItem.asset
        var specs = StreamSpecs(source: .measured)
        if let video = try? await asset.loadTracks(withMediaType: .video).first,
           let descriptions = try? await video.load(.formatDescriptions),
           let description = descriptions.first {
            let dimensions = CMVideoFormatDescriptionGetDimensions(description)
            if dimensions.width > 0 { specs.width = Int(dimensions.width) }
            if dimensions.height > 0 { specs.height = Int(dimensions.height) }
            let type = Self.fourCC(CMFormatDescriptionGetMediaSubType(description))
            specs.videoCodec = StreamSpecs.videoCodec(type)
            if StreamSpecs.isDolbyVision(fourCC: type) {
                specs.range = .dolbyVision
            } else if let transfer = CMFormatDescriptionGetExtension(description, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String {
                if transfer == kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String {
                    specs.range = .hdr10
                } else if transfer == kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String {
                    specs.range = .hlg
                } else if transfer == kCMFormatDescriptionTransferFunction_ITU_R_709_2 as String {
                    specs.range = .sdr
                }
            }
        }
        // An HLS stream lists no tracks: the size the item shows.
        if specs.width == nil, specs.height == nil {
            let size = playerItem.presentationSize
            if size.width > 0 { specs.width = Int(size.width) }
            if size.height > 0 { specs.height = Int(size.height) }
        }
        if let audio = try? await asset.loadTracks(withMediaType: .audio).first,
           let descriptions = try? await audio.load(.formatDescriptions),
           let description = descriptions.first {
            specs.audioCodec = StreamSpecs.audioCodec(Self.fourCC(CMFormatDescriptionGetMediaSubType(description)))
            if let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description)?.pointee, basic.mChannelsPerFrame > 0 {
                specs.channels = Int(basic.mChannelsPerFrame)
            }
        }
        guard playerItem == player.currentItem, !specs.isEmpty else { return }
        onSpecs?(item, specs)
    }

    /// A FourCC as text ("hvc1", "ec-3", "aac "), spaces trimmed.
    static func fourCC(_ code: FourCharCode) -> String {
        let bytes = [UInt8((code >> 24) & 0xFF), UInt8((code >> 16) & 0xFF), UInt8((code >> 8) & 0xFF), UInt8(code & 0xFF)]
        return String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
    }
}
