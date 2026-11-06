import { app, dialog, ipcMain, type BrowserWindow } from "electron";
import { autoUpdater } from "electron-updater";
import { IPC_CHANNELS } from "../constants";
import { getLogger } from "../utils/logger";

// The whole app updates itself: a new release downloads in the background and installs when dx.bi
// restarts. Works for the Windows installer and the Linux AppImage. macOS (apps must be signed by
// Apple's developer programme to replace themselves) and the Windows portable .exe keep the
// "Update available → Download" popup (core/Updater.ts).

const logger = getLogger("AutoUpdate");

export function canAutoUpdate(): boolean {
    if (!app.isPackaged) return false;
    if (process.platform === "win32") return !process.env.PORTABLE_EXECUTABLE_DIR;
    if (process.platform === "linux") return !!process.env.APPIMAGE;
    return false;
}

export function setupAutoUpdate(window: () => BrowserWindow | null): void {
    ipcMain.on(IPC_CHANNELS.AUTO_UPDATE_AVAILABLE, (event) => { event.returnValue = canAutoUpdate(); });
    if (!canAutoUpdate()) return;

    autoUpdater.logger = { info: (m: unknown) => logger.info(String(m)), warn: (m: unknown) => logger.warn(String(m)), error: (m: unknown) => logger.error(String(m)), debug: () => {} };
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;

    let askedByViewer = false;
    let offered: string | null = null;

    autoUpdater.on("update-available", (info) => {
        if (askedByViewer) {
            dialog.showMessageBox({ type: "info", title: "Update found", message: `Downloading Lumen ${info.version} in the background. It'll ask to restart when it's ready.`, buttons: ["OK"] });
        }
    });
    autoUpdater.on("update-not-available", () => {
        if (askedByViewer) dialog.showMessageBox({ type: "info", title: "No update available", message: `You're running the latest version (v${app.getVersion()}).`, buttons: ["OK"] });
        askedByViewer = false;
    });
    autoUpdater.on("error", (err) => {
        logger.error(`Update failed: ${err?.message || err}`);
        if (askedByViewer) dialog.showMessageBox({ type: "error", title: "Update check failed", message: "Couldn't check for updates right now. Please try again later.", buttons: ["OK"] });
        askedByViewer = false;
    });
    autoUpdater.on("update-downloaded", async (info) => {
        askedByViewer = false;
        if (offered === info.version) return;
        offered = info.version;
        const win = window();
        const options = {
            type: "info" as const,
            title: "Update ready",
            message: `Lumen ${info.version} is ready to install.`,
            detail: "Restart now to update, or it'll install the next time you close Lumen.",
            buttons: ["Restart now", "Later"],
            defaultId: 0,
            cancelId: 1,
        };
        const { response } = win ? await dialog.showMessageBox(win, options) : await dialog.showMessageBox(options);
        if (response === 0) autoUpdater.quitAndInstall(true, true);
    });

    ipcMain.on(IPC_CHANNELS.AUTO_UPDATE_CHECK, (_, viewerAsked: boolean) => {
        askedByViewer = !!viewerAsked;
        autoUpdater.checkForUpdates().catch((err) => logger.error(`Update check failed: ${err?.message || err}`));
    });
}
