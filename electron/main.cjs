const { app, BrowserWindow, dialog, session } = require("electron");
const { spawn } = require("node:child_process");
const http = require("node:http");
const path = require("node:path");

const PORT = 34123;
const APP_URL = `http://127.0.0.1:${PORT}`;
let server;

function appRoot() {
  return app.isPackaged ? path.join(process.resourcesPath, "app") : app.getAppPath();
}

function startServer() {
  const root = appRoot();
  const cli = path.join(root, "node_modules", "vinext", "dist", "cli.js");

  server = spawn(process.execPath, [cli, "start", "--port", String(PORT)], {
    cwd: root,
    env: {
      ...process.env,
      ELECTRON_RUN_AS_NODE: "1",
      HOST: "127.0.0.1",
      PORT: String(PORT),
      WRANGLER_LOG_PATH: path.join(app.getPath("userData"), "cupflow-server.log"),
    },
    stdio: "ignore",
  });
}

function isServerReady() {
  return new Promise((resolve) => {
    const request = http.get(APP_URL, (response) => {
      response.resume();
      resolve(response.statusCode && response.statusCode < 500);
    });
    request.setTimeout(500, () => request.destroy());
    request.on("error", () => resolve(false));
  });
}

async function waitForServer() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (await isServerReady()) return;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("CupFlow 本地服务启动超时");
}

function openWindow() {
  const window = new BrowserWindow({
    width: 1440,
    height: 940,
    minWidth: 1050,
    minHeight: 720,
    autoHideMenuBar: true,
    backgroundColor: "#f7f6f0",
    webPreferences: {
      contextIsolation: true,
      sandbox: false,
    },
  });
  window.loadURL(APP_URL);
}

app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_webContents, permission, callback) => {
    callback(permission === "media");
  });

  try {
    startServer();
    await waitForServer();
    openWindow();
  } catch (error) {
    dialog.showErrorBox("杯序 CupFlow 无法启动", `${error.message}\n请重新解压完整压缩包后再试。`);
    app.quit();
  }
});

app.on("window-all-closed", () => app.quit());
app.on("before-quit", () => server?.kill());
