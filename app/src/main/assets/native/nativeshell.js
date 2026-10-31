const features = [
    "castmenuhashchange",
    "clientsettings",
    "displaylanguage",
    "downloadmanagement",
    "exit",
    "externallinks",
    "filedownload",
    "fileinput",
    "htmlaudioautoplay",
    "htmlvideoautoplay",
    "multiserver",
    "physicalvolumecontrol",
    "remotecontrol",
    "subtitleappearancesettings",
    "subtitleburnsettings"
];

const plugins = [
    'NavigationPlugin',
    'ExoPlayerPlugin',
    'ExternalPlayerPlugin',
    'MediaSegmentsPlugin'
];

// Add plugin loaders
for (const plugin of plugins) {
    window[plugin] = async () => {
        const pluginDefinition = await import(`/native/${plugin}.js`);
        return pluginDefinition[plugin];
    };
}

const { deviceId, deviceName, appName, appVersion } = JSON.parse(window.NativeInterface.getDeviceInformation());
const codecCaps = JSON.parse(window.NativeInterface.getCodecCapabilities());

window.NativeShell = {
    enableFullscreen() {
        window.NativeInterface.enableFullscreen();
    },

    disableFullscreen() {
        window.NativeInterface.disableFullscreen();
    },

    openUrl(url, target) {
        window.NativeInterface.openUrl(url);
    },

    updateMediaSession(mediaInfo) {
        window.NativeInterface.updateMediaSession(JSON.stringify(mediaInfo));
    },

    hideMediaSession() {
        window.NativeInterface.hideMediaSession();
    },

    updateVolumeLevel(value) {
        window.NativeInterface.updateVolumeLevel(value);
    },

    downloadFile(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify([downloadInfo]));
    },

    downloadFiles(downloadInfo) {
        window.NativeInterface.downloadFiles(JSON.stringify(downloadInfo));
    },

    openDownloadManager() {
        window.NativeInterface.openDownloadManager();
    },

    openClientSettings() {
        window.NativeInterface.openClientSettings();
    },

    selectServer() {
        window.NativeInterface.openServerSelection();
    },

    getPlugins() {
        return plugins;
    },

    async execCast(action, args, callback) {
        this.castCallbacks = this.castCallbacks || {};
        this.castCallbacks[action] = callback;
        window.NativeInterface.execCast(action, JSON.stringify(args));
    },

    async castCallback(action, keep, err, result) {
        const callbacks = this.castCallbacks || {};
        const callback = callbacks[action];
        callback && callback(err || null, result);
        if (!keep) {
            delete callbacks[action];
        }
    }
};

function getDeviceProfile(profileBuilder, item) {
    return profileBuilder();
}

window.NativeShell.AppHost = {
    init() {},
    getDefaultLayout() {
        // TV build: 10-foot layout with remote (D-pad) focus navigation
        return "tv";
    },
    supports(command) {
        command = command.toLowerCase();
        if (command === "chromecast") {
            return window.NativeInterface.hasChromecast();
        }
        return features.includes(command);
    },
    getDeviceProfile,
    deviceName() {
        return deviceName;
    },
    deviceId() {
        return deviceId;
    },
    appName() {
        return appName;
    },
    appVersion() {
        return appVersion;
    },
    exit() {
        window.NativeInterface.exitApp();
    }
};

// ---------------------------------------------------------------------------
// TV build: interface size.
// Android TV draws apps as if a 4K/1080p screen were ~960px wide, so the web
// interface comes out huge. Lay it out at a desktop-like width instead and let
// the WebView scale it to fit, with a smaller base font for the TV layout.
// [layout width in CSS px, base font size in px]; null = Jellyfin's own TV sizing
// ---------------------------------------------------------------------------
const NEXUS_SIZES = {
    large: null,
    medium: [1600, 18],
    small: [1920, 16],
    xsmall: [1920, 14]
};
let nexusOriginalViewport = null;

window.nexusApplyInterfaceSize = function (sizeKey) {
    const key = sizeKey || window.NativeInterface.getInterfaceSize();
    const size = Object.prototype.hasOwnProperty.call(NEXUS_SIZES, key) ? NEXUS_SIZES[key] : NEXUS_SIZES.small;

    let meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
        meta = document.createElement('meta');
        meta.name = 'viewport';
        document.head.appendChild(meta);
    }
    if (nexusOriginalViewport === null) nexusOriginalViewport = meta.content;

    let style = document.getElementById('nexus-interface-size');
    if (!style) {
        style = document.createElement('style');
        style.id = 'nexus-interface-size';
        document.head.appendChild(style);
    }

    if (!size) {
        meta.content = nexusOriginalViewport;
        style.textContent = '';
    } else {
        meta.content = `width=${size[0]}, user-scalable=no, viewport-fit=cover`;
        style.textContent = `html.layout-tv { font-size: ${size[1]}px !important; }`;
    }
};

window.nexusApplyInterfaceSize();
