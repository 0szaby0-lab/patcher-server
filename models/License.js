const mongoose = require('mongoose');

const licenseSchema = new mongoose.Schema({
  key: { type: String, required: true, unique: true },
  durationDays: { type: Number, required: true }, // 30, 180, 365
  hardwareId: { type: String, default: null }, // Null amíg nincs aktiválva
  ipAddress: { type: String, default: null },
  location: { type: String, default: null }, // Város, Ország
  createdAt: { type: Date, default: Date.now },
  activatedAt: { type: Date, default: null },
  expiresAt: { type: Date, default: null },
  isActive: { type: Boolean, default: true }
});

module.exports = mongoose.model('License', licenseSchema);
