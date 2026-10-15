import SwiftUI
import AVKit
import IPTVCore
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#endif

/// Player UI (full screen on iPhone and iPad, a window on the Mac): video, auto-hiding controls, zapping list,
/// in-player guide, lock mode (iPhone and iPad).
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled
    let session: PlaybackSession
    var onClose: () -> Void

    @State private var controlsVisible = true
    #if os(macOS)
    @State private var lastPointerMove = Date.distantPast
    #endif
    @State private var hideTask: Task<Void, Never>?
    @State private var isLocked = false
    @State private var panelTab: PlayerPanelTab?
    @State private var typedNumber = ""
    @State private var numberTask: Task<Void, Never>?
    @State private var scrubPosition: Double?
    @State private var isScrubbingLive = false
    @State private var showingSubtitleImporter = false
    @State private var explainingAirPlay = false
    @State private var searchingSubtitles = false
    #if os(macOS)
    @State private var window = PlayerWindowState()
    #endif
    @FocusState private var focused: Bool

    private var controller: PlayerController { session.controller }
    private var cast: CastManager { CastManager.shared }
    private var item: PlaybackItem? { session.current }

    private var channel: Channel? {
        guard let item, item.reference.type == .live else { return nil }
        return model.index.channelsByID[item.reference.itemID]
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(controller: controller)
                .ignoresSafeArea()
            #if canImport(VLCKitSPM)
            // Always present so VLC has a drawable before it starts (otherwise it opens its own window).
            VLCVideoSurface(controller: controller)
                .ignoresSafeArea()
                .opacity(controller.activeEngine == .vlc ? 1 : 0)
                .allowsHitTesting(false)
            #endif

            PlayerGestureLayer(controller: controller, onTap: toggleControls, onDoubleTapCenter: handleDoubleTap)

            statusOverlay

            if controller.showsStatistics {
                VStack {
                    HStack {
                        PlayerStatsView(controller: controller)
                        Spacer()
                    }
                    Spacer()
                }
                .padding(.top, controlsVisible ? 70 : 16)
                .padding(.horizontal, 16)
            }

            if let item = session.playingItem, cast.castingItemID == item.id {
                CastingOverlay(cast: cast, onStop: stopCasting)
            } else if controller.isExternalPlaybackActive {
                AirPlayOverlay()
            }

            if let cue = controller.currentExternalCue {
                VStack {
                    Spacer()
                    Text(cue.text)
                        .font(.system(size: Platform.isMac ? 26 : 20, weight: .semibold))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .shadow(color: .black, radius: 2)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
                        .padding(.bottom, controlsVisible ? 110 : 40)
                }
                .allowsHitTesting(false)
            }

            if isLocked {
                lockOverlay
            } else if controlsVisible {
                controlsOverlay
                    .transition(.opacity)
            }

            if let tab = panelTab, !isLocked {
                HStack {
                    Spacer()
                    PlayerSidePanel(
                        session: session,
                        tab: Binding(get: { tab }, set: { panelTab = $0 })
                    ) {
                        panelTab = nil
                        scheduleHide()
                    }
                    .frame(maxWidth: 360)
                    .transition(.move(edge: .trailing))
                }
            }

            if !typedNumber.isEmpty {
                numberEntryOverlay
            }
        }
        .focusedSceneValue(\.playbackSession, session)
        // VoiceOver: the escape gesture closes the player; on iPhone and iPad a two-finger double tap
        // plays or pauses (the Mac has no magic tap).
        .accessibilityAction(.escape) { onClose() }
        #if os(iOS)
        .accessibilityAction(.magicTap) { session.togglePlayPause() }
        #else
        // Moving the pointer over the picture brings the controls back, like the Mac's own players.
        .onContinuousHover { phase in
            guard case .active = phase else { return }
            // Shown again at once; kept while the pointer moves (the hide timer restarts at most once a second).
            if !controlsVisible || Date().timeIntervalSince(lastPointerMove) > 1 {
                lastPointerMove = Date()
                showControls()
            }
        }
        #endif
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .animation(.easeInOut(duration: 0.25), value: panelTab)
        .immersive()
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onKeyPress(action: handleKey)
        .onAppear {
            focused = true
            scheduleHide()
            #if os(iOS)
            if !Platform.isPad, item?.isLive == true ? model.settings.landscapeForLive : model.settings.landscapeForVOD {
                OrientationController.lockLandscape()
            }
            #endif
        }
        .onDisappear {
            #if os(iOS)
            if !Platform.isPad { OrientationController.unlock() }
            #endif
        }
        .onChange(of: controller.state) { _, state in
            if case .failed = state { showControls(autoHide: false) }
        }
        .task(id: channel?.id) {
            if let channel { await model.ensureGuide(for: channel) }
        }
        .onChange(of: cast.isConnected) { _, connected in
            // Stopping from the player resumes here (stopCasting); a cast ended elsewhere leaves the player paused.
            if connected { castCurrentItem() }
        }
        .onChange(of: session.playingItem) { _, _ in
            if cast.isConnected { castCurrentItem() }
        }
        .onAppear {
            if cast.isConnected { castCurrentItem() }
        }
        #if os(macOS)
        .background(WindowReader { window.attach($0) })
        #endif
        .sheet(isPresented: $searchingSubtitles) {
            if let item = session.playingItem {
                SubtitleSearchView(controller: controller, item: item)
            }
        }
        .alert("AirPlay needs the Apple engine", isPresented: $explainingAirPlay) {
            Button("Use the Apple Engine") { controller.switchEngine(to: .apple) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This stream is played by VLC, which shows the picture on this device only. The Apple engine can send it to your TV if it can open the stream.")
        }
        .fileImporter(isPresented: $showingSubtitleImporter, allowedContentTypes: Self.subtitleTypes) { result in
            guard case .success(let url) = result else { return }
            try? controller.loadExternalSubtitles(from: url)
        }
    }

    // MARK: - Overlays

    @ViewBuilder
    private var statusOverlay: some View {
        switch controller.state {
        case .loading:
            ProgressView()
                .controlSize(.large)
                .tint(.white)
        case .failed(let message, let attribution):
            VStack(spacing: 14) {
                Image(systemName: attribution.systemImage)
                    .font(.system(size: 40))
                Text(attribution.label).font(.headline)
                Text(message)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.8))
                    .frame(maxWidth: 460)
                HStack {
                    Button("Retry") { controller.retry() }
                        .buttonStyle(.borderedProminent)
                    if session.isTimeshifted {
                        Button("Back to Live") { session.returnToLive() }
                            .buttonStyle(.bordered)
                    } else if session.queue.count > 1, session.isLiveChannel {
                        Button("Next Channel") { session.zap(1) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            .foregroundStyle(.white)
            .padding(24)
            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
        default:
            if controller.isBuffering {
                ProgressView().tint(.white).controlSize(.large)
            }
        }
    }

    private var lockOverlay: some View {
        VStack {
            HStack {
                Button {
                    isLocked = false
                    showControls()
                } label: {
                    Label("Unlock", systemImage: "lock.fill")
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                Spacer()
            }
            Spacer()
        }
        .padding()
    }

    private var controlsOverlay: some View {
        GeometryReader { proxy in
            // iPhone portrait cannot fit every action in the top bar: secondary ones move above the info.
            let compact = proxy.size.width < 600
            VStack(spacing: 0) {
                topBar(compact: compact)
                    .padding(.horizontal)
                    .padding(.top, 8)
                    .padding(.bottom, 30)
                    .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom))
                Spacer()
                centerControls
                Spacer()
                VStack(spacing: 14) {
                    if compact {
                        HStack(spacing: 0) {
                            secondaryActions(spread: true)
                        }
                        .font(.title3)
                        .frame(maxWidth: .infinity)
                    }
                    bottomBar
                }
                .padding(.horizontal)
                .padding(.bottom, 12)
                .padding(.top, 30)
                .background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom))
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .foregroundStyle(.white)
        .buttonStyle(.plain)
        .menuStyle(.button)
        .menuIndicator(.hidden)
    }

    private func topBar(compact: Bool) -> some View {
        HStack(spacing: 18) {
            Button(action: closeOrLeaveFullScreen) {
                Image(systemName: Platform.isMac ? "xmark" : "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 36, height: 36)
            }
            .keyboardShortcut(.cancelAction)
            .help("Close")
            .accessibilityLabel("Close")
            .accessibilityIdentifier("playerClose")

            titleBlock
                .layoutPriority(-1)

            Spacer(minLength: 0)

            if !compact {
                secondaryActions(spread: false)
            }
            #if canImport(GoogleCast)
            CastButton()
                .frame(width: 30, height: 30)
            #endif
            if controller.activeEngine == .vlc {
                // VLC draws on this device only: AirPlay would connect the TV without a picture.
                Button {
                    explainingAirPlay = true
                } label: {
                    Image(systemName: "airplayvideo")
                }
                .help("AirPlay")
                .accessibilityLabel("AirPlay")
            } else {
                RoutePickerButton(player: controller.player)
                    .frame(width: 30, height: 30)
                    .help("AirPlay")
            }
            #if os(macOS)
            Button {
                window.toggleFullScreen()
            } label: {
                Image(systemName: window.isFullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
            }
            .help(window.isFullScreen ? Text("Exit Full Screen (F)") : Text("Full Screen (F)"))
            .accessibilityLabel(window.isFullScreen ? Text("Exit Full Screen") : Text("Full Screen"))
            .accessibilityIdentifier("playerFullScreen")
            #endif
            PlayerMoreMenu(controller: controller, item: session.playingItem)
        }
        .font(.title3)
    }

    private var titleBlock: some View {
        HStack(spacing: 10) {
            if let channel {
                RemoteImage(url: model.logoURL(for: channel), placeholderText: channel.name)
                    .frame(width: 44, height: 30)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(session.playingItem?.title ?? "")
                    .font(.headline)
                    .lineLimit(1)
                if let subtitle = session.playingItem?.subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.75))
                        .lineLimit(1)
                }
            }
        }
    }

    /// Actions shown in the top bar on wide screens, in their own row on narrow ones.
    @ViewBuilder
    private func secondaryActions(spread: Bool) -> some View {
        if let channel {
            Button {
                model.toggleFavorite(channel)
            } label: {
                Image(systemName: model.isFavorite(.live, id: channel.id) ? "checkmark" : "plus")
            }
            .help("My List")
            .accessibilityLabel("My List")
            .accessibilityValue(model.isFavorite(.live, id: channel.id) ? Text("In My List") : Text(""))
            gap(spread)
        }
        if session.isLiveChannel && session.queue.count > 1 {
            Button {
                togglePanel(.channels)
            } label: {
                Image(systemName: "list.bullet")
            }
            .help("Channels")
            .accessibilityLabel("Channels")
            gap(spread)
        }
        if let channel, AppModel.supportsMultiview {
            Button {
                model.addToMultiview(channel)
                #if os(macOS)
                onClose()
                #endif
            } label: {
                Image(systemName: "rectangle.split.2x2")
            }
            .help("Add to Multiview")
            .accessibilityLabel("Add to Multiview")
            gap(spread)
        }
        if channel != nil {
            Button {
                togglePanel(.guide)
            } label: {
                Image(systemName: "calendar.day.timeline.left")
            }
            .help("Guide")
            .accessibilityLabel("Guide")
            .accessibilityIdentifier("playerGuide")
            gap(spread)
        }
        PlayerTracksMenu(controller: controller, onImportSubtitles: { showingSubtitleImporter = true }, onSearchSubtitles: { searchingSubtitles = true })
        gap(spread)
        Button {
            controller.aspect = controller.aspect.next
            scheduleHide()
        } label: {
            Image(systemName: controller.aspect.systemImage)
        }
        .help("Aspect ratio")
        .accessibilityLabel("Aspect ratio")
        if controller.canUsePictureInPicture {
            gap(spread)
            Button {
                controller.togglePictureInPicture()
            } label: {
                Image(systemName: controller.isPictureInPictureActive ? "pip.exit" : "pip.enter")
            }
            .help("Picture in Picture")
            .accessibilityLabel("Picture in Picture")
        }
        #if os(iOS)
        gap(spread)
        Button {
            isLocked = true
            panelTab = nil
        } label: {
            Image(systemName: "lock.open")
        }
        .accessibilityLabel("Lock Controls")
        #endif
    }

    private func togglePanel(_ tab: PlayerPanelTab) {
        panelTab = panelTab == tab ? nil : tab
        if panelTab != nil { hideTask?.cancel() } else { scheduleHide() }
    }

    @ViewBuilder
    private func gap(_ spread: Bool) -> some View {
        if spread { Spacer(minLength: 0) }
    }

    /// Live at its edge zaps with the side buttons; everything seekable (VOD, replay, DVR) skips.
    private var zapsFromCenter: Bool { session.isAtLiveEdge && !controller.hasLiveWindow }

    @ViewBuilder
    private var centerControls: some View {
        HStack(spacing: 48) {
            if zapsFromCenter {
                Button { session.zap(-1); scheduleHide() } label: {
                    Image(systemName: "chevron.up.circle").font(.system(size: 34))
                }
                .disabled(session.queue.count < 2)
                .help("Previous channel")
                .accessibilityLabel("Previous channel")
            } else {
                Button { controller.skip(by: -10); scheduleHide() } label: {
                    Image(systemName: "gobackward.10").font(.system(size: 34))
                }
                .disabled(!controller.canSkip)
                .accessibilityLabel("Back 10 seconds")
            }

            Button {
                session.togglePlayPause()
                scheduleHide()
            } label: {
                Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 46))
                    .frame(width: 64, height: 64)
            }
            .accessibilityLabel(controller.isPlaying ? Text("Pause") : Text("Play"))
            .accessibilityIdentifier("playPause")

            if zapsFromCenter {
                Button { session.zap(1); scheduleHide() } label: {
                    Image(systemName: "chevron.down.circle").font(.system(size: 34))
                }
                .disabled(session.queue.count < 2)
                .help("Next channel")
                .accessibilityLabel("Next channel")
            } else {
                Button { controller.skip(by: 30); scheduleHide() } label: {
                    Image(systemName: "goforward.30").font(.system(size: 34))
                }
                .disabled(!controller.canSkip)
                .accessibilityLabel("Forward 30 seconds")
            }
        }
        .shadow(radius: 8)
    }

    @ViewBuilder
    private var bottomBar: some View {
        if session.isTimeshifted {
            VStack(spacing: 8) {
                TimeshiftHeader(session: session, onInteraction: scheduleHide)
                seekBar
            }
        } else if session.isLiveChannel {
            LiveInfoBar(session: session, channel: channel, onInteraction: scheduleHide) { editing in
                isScrubbingLive = editing
                if editing { hideTask?.cancel() } else { scheduleHide() }
            }
        } else {
            seekBar
        }
    }

    private var seekBar: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: { scrubPosition ?? controller.currentTime },
                    set: { scrubPosition = $0 }
                ),
                in: 0...max(controller.duration, 1)
            ) { editing in
                if editing {
                    hideTask?.cancel()
                } else if let position = scrubPosition {
                    controller.seek(to: position)
                    scrubPosition = nil
                    scheduleHide()
                }
            }
            .disabled(!controller.canSeek)
            .tint(session.isTimeshifted ? .orange : .white)
            HStack {
                Text(Formatting.clock(scrubPosition ?? controller.currentTime))
                Spacer()
                if let end = controller.sleepTimerEnd {
                    Label(Formatting.time(end), systemImage: "moon.zzz")
                }
                Spacer()
                Text("-" + Formatting.clock(max(controller.duration - (scrubPosition ?? controller.currentTime), 0)))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.white.opacity(0.8))
        }
    }

    // MARK: - Number zapping

    private var queueNumbers: [Int?] {
        session.queue.map { model.index.channelsByID[$0.reference.itemID]?.number }
    }

    private var typedChannelPosition: Int? {
        Int(typedNumber).flatMap { ChannelNumbering.position(of: $0, numbers: queueNumbers) }
    }

    private var numberEntryOverlay: some View {
        VStack {
            HStack {
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text(typedNumber)
                        .font(.system(size: 44, weight: .bold, design: .rounded).monospacedDigit())
                    Text(typedChannelPosition.map { session.queue[$0].title } ?? "—")
                        .font(.headline)
                        .lineLimit(1)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
            }
            Spacer()
        }
        .padding(24)
        .allowsHitTesting(false)
    }

    private func typeDigit(_ digit: Character) {
        guard typedNumber.count < 5 else { return }
        typedNumber.append(digit)
        numberTask?.cancel()
        numberTask = Task {
            try? await Task.sleep(for: .seconds(1.6))
            guard !Task.isCancelled else { return }
            commitTypedNumber()
        }
    }

    private func commitTypedNumber() {
        numberTask?.cancel()
        if let position = typedChannelPosition { session.select(index: position) }
        typedNumber = ""
    }

    private func restartCurrentProgram() {
        guard let channel, let program = model.epg.current(for: channel) else { return }
        session.play(program)
    }

    // MARK: - Casting

    private func castCurrentItem() {
        guard let item = session.playingItem else { return }
        if cast.cast(item, from: controller.isLive ? nil : controller.currentTime) {
            controller.pause()
        }
    }

    private func stopCasting() {
        let position = cast.stopCasting()
        if let position, !controller.isLive { controller.seek(to: position) }
        controller.play()
    }

    // MARK: - Behaviour

    private func toggleControls() {
        if isLocked { return }
        if controlsVisible {
            controlsVisible = false
            hideTask?.cancel()
        } else {
            showControls()
        }
    }

    private func showControls(autoHide: Bool = true) {
        controlsVisible = true
        if autoHide { scheduleHide() } else { hideTask?.cancel() }
    }

    #if DEBUG
    /// UI tests drive the controls step by step: they must not fade out in between.
    private static let controlsStayVisible = ProcessInfo.processInfo.arguments.contains("-uitest-fixture")
    #else
    private static let controlsStayVisible = false
    #endif

    private func scheduleHide() {
        hideTask?.cancel()
        // With VoiceOver the controls never fade: there would be no way to bring them back.
        guard !Self.controlsStayVisible, !voiceOverEnabled else { return }
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, controller.isPlaying, scrubPosition == nil, !isScrubbingLive, panelTab == nil else { return }
            controlsVisible = false
            #if os(macOS)
            NSCursor.setHiddenUntilMouseMoves(true)  // nothing over the picture, the pointer included
            #endif
        }
    }
}

// MARK: - Keyboard, double click, full screen

extension PlayerView {
    /// Esc (and the close button) leave full screen first, like the Mac's own players; then close.
    private func closeOrLeaveFullScreen() {
        #if os(macOS)
        if window.isFullScreen {
            window.toggleFullScreen()
            return
        }
        #endif
        onClose()
    }

    private func handleDoubleTap() {
        #if os(macOS)
        window.toggleFullScreen()
        #else
        controller.aspect = controller.aspect == .fit ? .fill : .fit
        #endif
    }

    private func handleKey(_ press: KeyPress) -> KeyPress.Result {
        let isLiveChannel = session.isLiveChannel
        if isLiveChannel, press.characters.count == 1, let digit = press.characters.first, digit.isASCII, digit.isNumber {
            typeDigit(digit)
            return .handled
        }
        switch press.key {
        case .space:
            session.togglePlayPause()
        case .return where !typedNumber.isEmpty:
            commitTypedNumber()
            return .handled
        case .upArrow where isLiveChannel:
            session.zap(-1)
        case .downArrow where isLiveChannel:
            session.zap(1)
        case .leftArrow where controller.canSkip:
            controller.skip(by: -10)
        case .rightArrow where controller.canSkip:
            controller.skip(by: 10)
        case .delete where isLiveChannel:
            session.returnToPreviousChannel()
        case .escape:
            if panelTab != nil { panelTab = nil } else { closeOrLeaveFullScreen() }
        default:
            guard handleShortcut(press.characters.lowercased()) else { return .ignored }
        }
        showControls()
        return .handled
    }

    /// Single-letter shortcuts (F full screen on the Mac, fit / fill on iPhone and iPad, M mute, L channels,
    /// G guide, R restart programme).
    private func handleShortcut(_ key: String) -> Bool {
        switch key {
        case "f":
            handleDoubleTap()
        case "m":
            controller.isMuted.toggle()
        case "l" where session.isLiveChannel:
            togglePanel(.channels)
        case "g" where channel != nil:
            togglePanel(.guide)
        case "r" where session.isLiveChannel:
            restartCurrentProgram()
        default:
            return false
        }
        return true
    }
}

extension PlayerView {
    static let subtitleTypes: [UTType] = {
        var types: [UTType] = [.plainText, .text]
        for ext in ["srt", "vtt", "webvtt"] {
            if let type = UTType(filenameExtension: ext) { types.insert(type, at: 0) }
        }
        return types
    }()
}

#if os(macOS)
/// Content of a macOS player window.
struct PlayerWindow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let sessionID: UUID?

    var body: some View {
        if let sessionID, let session = model.session(id: sessionID) {
            PlayerView(session: session) { dismiss() }
                .navigationTitle(session.current?.title ?? "")
                .frame(minWidth: 480, minHeight: 270)
                .onDisappear { model.endSession(session) }
        } else {
            ContentUnavailableView("Nothing to play", systemImage: "play.slash")
                .frame(minWidth: 480, minHeight: 270)
        }
    }
}
#endif

/// Shown while AirPlay plays the stream on a TV: the picture is there, not here.
struct AirPlayOverlay: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "airplayvideo")
                .font(.system(size: 44))
            Text("Playing on AirPlay")
                .font(.headline)
            Text("The picture is on your TV. The controls here still work.")
                .font(.callout)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.75))
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 22))
        .allowsHitTesting(false)
    }
}

/// Shown over the (paused) local player while the stream plays on a Chromecast.
struct CastingOverlay: View {
    let cast: CastManager
    var onStop: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "tv.and.mediabox")
                .font(.system(size: 44))
            Text("Casting to \(cast.deviceName ?? String(localized: "your TV"))")
                .font(.headline)
            if let error = cast.lastError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.yellow)
                    .frame(maxWidth: 420)
            }
            HStack(spacing: 36) {
                Button { cast.seek(by: -30) } label: { Image(systemName: "gobackward.30") }
                    .accessibilityLabel("Back 30 seconds")
                Button { cast.togglePlayPause() } label: {
                    Image(systemName: cast.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 52))
                }
                .accessibilityLabel(cast.isPlaying ? Text("Pause") : Text("Play"))
                Button { cast.seek(by: 30) } label: { Image(systemName: "goforward.30") }
                    .accessibilityLabel("Forward 30 seconds")
            }
            .font(.title)
            Button("Stop Casting", role: .destructive, action: onStop)
                .buttonStyle(.bordered)
        }
        .foregroundStyle(.white)
        .padding(28)
        .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 22))
        .buttonStyle(.plain)
    }
}
