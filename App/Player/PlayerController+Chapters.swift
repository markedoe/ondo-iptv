import AVFoundation
import IPTVCore

/// Where the end credits of a film or an episode start, from its chapters (`CreditsChapter`): the title counts as
/// watched once playback reaches them (`HistoryEntry.isFinished`). AVPlayer reads an MP4 or MOV file's chapters, the
/// remux an MKV file's (PlayerController+Remux), VLC those of what it plays.
extension PlayerController {
    /// From the chapters AVPlayer reads; a file without a credits chapter (or the remuxed MKV film's playlist, which
    /// has none) keeps what is known.
    func readCreditsStart(of playerItem: AVPlayerItem) async {
        guard !isLive, duration > 0,
              let groups = try? await playerItem.asset.loadChapterMetadataGroups(bestMatchingPreferredLanguages: Locale.preferredLanguages),
              !groups.isEmpty else { return }
        var chapters: [MediaChapter] = []
        for group in groups {
            let titleItem = AVMetadataItem.metadataItems(from: group.items, filteredByIdentifier: .commonIdentifierTitle).first
            let title = try? await titleItem?.load(.stringValue)
            chapters.append(MediaChapter(title: title, start: group.timeRange.start.seconds))
        }
        guard playerItem == player.currentItem, let credits = CreditsChapter.start(in: chapters, duration: duration) else { return }
        creditsStart = credits
    }

    /// From VLC's chapters, once it plays and knows the duration: looked at once per item.
    func readVLCCreditsStart() {
        #if canImport(VLCKitSPM)
        guard !vlcChaptersRead, duration > 0, let backend = vlcBackend else { return }
        vlcChaptersRead = true
        if !isLive, let credits = CreditsChapter.start(in: backend.chapters, duration: duration) { creditsStart = credits }
        #endif
    }
}
