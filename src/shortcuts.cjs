const isTwoKey = key => typeof key === 'string' && /^(Alt|Control)\+[A-Z0-9]$/.test(key);
const migrateHotkey = key => isTwoKey(key) ? key : 'Alt+S';
module.exports = { isTwoKey, migrateHotkey };
