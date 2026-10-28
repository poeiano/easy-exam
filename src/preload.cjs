const { contextBridge, ipcRenderer } = require('electron');
const methods = ['status', 'screens', 'start', 'stop', 'forget-devices', 'direct-rotate', 'direct-qr', 'direct-copy', 'connect-code', 'cancel-pair', 'disconnect', 'hide', 'capture', 'long-capture', 'cancel-long-capture', 'settings'];
const api = Object.fromEntries(methods.map(name => [name.replace(/-([a-z])/g, (_m, x) => x.toUpperCase()), (...args) => ipcRenderer.invoke(name, ...args)]));
api.onChange = fn => { const listener = () => fn(); ipcRenderer.on('state-changed', listener); return () => ipcRenderer.removeListener('state-changed', listener); };
contextBridge.exposeInMainWorld('desktop', api);
