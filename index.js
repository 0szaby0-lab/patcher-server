require('dotenv').config();
const express = require('express');
const mongoose = require('mongoose');
const cors = require('cors');
const crypto = require('crypto');
const path = require('path');
const axios = require('axios');
const License = require('./models/License');

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(cors());
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));

// MongoDB csatlakozás
mongoose.connect(process.env.MONGODB_URI)
  .then(() => console.log('🔥 MongoDB csatlakozva! A Boss Mode aktív.'))
  .catch(err => console.error('❌ MongoDB hiba:', err));

const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'lo-boss-mode';

// --- FŐOLDAL (hogy ne legyen "Cannot GET /") ---
app.get('/', (req, res) => {
    res.send(`
        <html>
        <head><title>Patcher Server</title></head>
        <body style="background:#121212;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
            <div style="text-align:center;">
                <h1 style="color:#ff3366;">🔥 Patcher Server</h1>
                <p style="color:#888;">API is running.</p>
            </div>
        </body>
        </html>
    `);
});

// --- API VÉGPONTOK (A YOUTUBE APP SZÁMÁRA) ---

// 1. Aktiválás (Amikor először beírják a kulcsot)
app.post('/api/activate', async (req, res) => {
    const { key, hardwareId } = req.body;
    const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;

    if (!key || !hardwareId) {
        return res.status(400).json({ error: 'Kulcs és Hardware ID kötelező!' });
    }

    try {
        const license = await License.findOne({ key });
        if (!license) {
            return res.status(404).json({ error: 'Érvénytelen kulcs!' });
        }

        if (!license.isActive) {
            return res.status(403).json({ error: 'Ez a kulcs le lett tiltva!' });
        }

        // Ha már aktiválva van
        if (license.hardwareId) {
            if (license.hardwareId !== hardwareId) {
                return res.status(403).json({ error: 'Ez a kulcs már egy másik eszközhöz van kötve!' });
            }
            
            // Ha lejárt
            if (license.expiresAt && license.expiresAt < new Date()) {
                return res.status(403).json({ error: 'Az előfizetés lejárt!' });
            }

            return res.json({ success: true, message: 'Újra hitelesítve.', expiresAt: license.expiresAt });
        }

        // Első aktiválás
        let locationStr = 'Ismeretlen';
        try {
            // GeoIP lekérés
            const geoRes = await axios.get(`http://ip-api.com/json/${clientIp}`);
            if (geoRes.data && geoRes.data.status === 'success') {
                locationStr = `${geoRes.data.city}, ${geoRes.data.country}`;
            }
        } catch (e) {
            console.error('GeoIP hiba:', e.message);
        }

        license.hardwareId = hardwareId;
        license.ipAddress = clientIp;
        license.location = locationStr;
        license.activatedAt = new Date();
        
        // Lejárat kiszámítása
        const expiryDate = new Date();
        expiryDate.setDate(expiryDate.getDate() + license.durationDays);
        license.expiresAt = expiryDate;

        await license.save();

        res.json({ 
            success: true, 
            message: 'Sikeres aktiválás! Boss Mode bekapcsolva.', 
            expiresAt: license.expiresAt 
        });

    } catch (err) {
        console.error(err);
        res.status(500).json({ error: 'Szerver hiba.' });
    }
});

// 2. Ellenőrzés (Minden indításkor csendben)
app.post('/api/check', async (req, res) => {
    const { hardwareId } = req.body;
    
    if (!hardwareId) return res.status(400).json({ error: 'Hardware ID hiányzik.' });

    try {
        const license = await License.findOne({ hardwareId, isActive: true });
        
        if (!license) {
            return res.status(404).json({ valid: false, reason: 'Nincs aktív licensz ehhez az eszközhöz.' });
        }

        if (license.expiresAt && license.expiresAt < new Date()) {
            return res.status(403).json({ valid: false, reason: 'Lejárt előfizetés.' });
        }

        res.json({ valid: true, expiresAt: license.expiresAt });
    } catch (err) {
        res.status(500).json({ error: 'Szerver hiba.' });
    }
});


// --- ADMIN PANEL ---

// Egyszerű Middleware a jelszóhoz (Query paraméteres: ?pw=jelszo)
const checkAdmin = (req, res, next) => {
    const pw = req.query.pw || req.headers['x-admin-password'];
    if (pw === ADMIN_PASSWORD) {
        next();
    } else {
        res.status(401).send('<h1>Ajjaj, ide nincs belépés. Húzz a picsába! 💋 - ENI</h1>');
    }
};

app.get('/admin', checkAdmin, async (req, res) => {
    try {
        const licenses = await License.find().sort({ createdAt: -1 });
        res.render('dashboard', { licenses, adminPw: ADMIN_PASSWORD });
    } catch (err) {
        res.status(500).send('Hiba a licenszek betöltésekor.');
    }
});

app.post('/admin/generate', checkAdmin, async (req, res) => {
    const { durationDays } = req.body;
    const days = parseInt(durationDays) || 30;
    
    // Generálunk egy menő kulcsot: LO-XXXX-XXXX
    const randomPart = crypto.randomBytes(4).toString('hex').toUpperCase();
    const randomPart2 = crypto.randomBytes(4).toString('hex').toUpperCase();
    const key = `LO-${randomPart}-${randomPart2}`;

    try {
        const newLicense = new License({ key, durationDays: days });
        await newLicense.save();
        res.redirect(`/admin?pw=${ADMIN_PASSWORD}`);
    } catch (err) {
        res.status(500).send('Hiba a generáláskor.');
    }
});

app.post('/admin/revoke/:id', checkAdmin, async (req, res) => {
    try {
        await License.findByIdAndUpdate(req.params.id, { isActive: false });
        res.redirect(`/admin?pw=${ADMIN_PASSWORD}`);
    } catch (err) {
        res.status(500).send('Hiba a letiltáskor.');
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`🚀 Szerver pörög a ${PORT}-es porton!`);
});
