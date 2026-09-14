const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  call: (method, endpoint, data) => 
    ipcRenderer.invoke('api-call', method, endpoint, data),
  
  startTracking: () => 
    ipcRenderer.invoke('api-call', 'POST', '/api/start', {}),
  
  stopTracking: () => 
    ipcRenderer.invoke('api-call', 'POST', '/api/stop', {}),
  
  getStatus: () => 
    ipcRenderer.invoke('api-call', 'GET', '/api/status', {}),
  
  getLatestFrame: () => 
    fetch('http://localhost:5000/api/latest-frame'),
  
  getTrackedObjects: () => 
    ipcRenderer.invoke('api-call', 'GET', '/api/tracked-objects', {}),
  
  getAnalytics: () => 
    ipcRenderer.invoke('api-call', 'GET', '/api/analytics/summary', {}),
  
  getReport: () => 
    ipcRenderer.invoke('api-call', 'GET', '/api/analytics/report', {})
});
