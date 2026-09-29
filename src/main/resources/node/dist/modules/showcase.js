"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const fs = require("fs");
const crypto = require("crypto");
// Minecraft "gallery" service: the same place the game reads an account's showcase image from.
// (FC_GALLERY_URL exists only so the upload logic can be tested against a local mock server.)
const GALLERY = process.env.FC_GALLERY_URL ??
    "https://persona.franchise.minecraft-services.net/api/v1.0/gallery";
class Showcase {
    static log(message) {
        console.log(`[FC-IMAGE] ${message}`);
    }
    static warn(message) {
        console.log(`[FC-IMAGE-WARN] ${message}`);
    }
    /**
     * Uploads `imagePath` as the host account's featured (showcase) image and removes older ones.
     * Never throws: a problem with the image must not stop Friend Connect itself.
     */
    static async Apply(authflow, xuid, imagePath, statePath) {
        try {
            if (!imagePath)
                return;
            if (!fs.existsSync(imagePath)) {
                this.log(`No image found at ${imagePath} - skipping (this is optional).`);
                return;
            }
            const data = fs.readFileSync(imagePath);
            if (data.length < 3 || data[0] !== 0xff || data[1] !== 0xd8) {
                this.warn("The image is not a JPEG file. Use a .jpg (1200x675 works best) - skipping upload.");
                return;
            }
            const hash = crypto.createHash("sha256").update(data).digest("hex");
            const services = await authflow.getMinecraftBedrockServicesToken({
                version: "1.21.100",
            });
            const headers = { Authorization: services.mcToken };
            // What is currently on the account?
            const listResponse = await fetch(`${GALLERY}/xuid/${xuid}`, {
                headers,
                signal: AbortSignal.timeout(30000),
            });
            if (!listResponse.ok) {
                this.warn(`Could not read the current gallery (HTTP ${listResponse.status}, you may be rate limited). Will retry on next start.`);
                return;
            }
            const list = await listResponse.json();
            const existing = list?.result?.showcasedImages ?? [];
            // Skip if this exact file is already uploaded.
            let state = {};
            try {
                state = JSON.parse(fs.readFileSync(statePath, "utf8"));
            }
            catch {
                // first run
            }
            if (state.hash === hash && existing.some((image) => image.id === state.id)) {
                this.log("Image is already set - nothing to upload.");
                return;
            }
            const upload = await fetch(GALLERY, {
                method: "POST",
                headers: {
                    ...headers,
                    "content-type": "application/octet-stream",
                    "x-ms-showcased-featured": "true",
                    "x-ms-showcased-timetaken": fs.statSync(imagePath).mtime.toISOString(),
                },
                body: data,
                signal: AbortSignal.timeout(60000),
            });
            if (upload.status !== 202) {
                this.warn(`Upload failed (HTTP ${upload.status}): ${(await upload.text()).slice(0, 200)}`);
                return;
            }
            const uploaded = await upload.json();
            const newId = uploaded?.result?.id;
            if (!newId) {
                this.warn("Upload was accepted but no image id came back.");
                return;
            }
            // Keep only the new image.
            for (const image of existing) {
                if (image.id === newId)
                    continue;
                await fetch(`${GALLERY}/${image.id}`, {
                    method: "DELETE",
                    headers,
                    signal: AbortSignal.timeout(30000),
                }).catch(() => undefined);
            }
            fs.writeFileSync(statePath, JSON.stringify({ hash, id: newId }));
            this.log("Image uploaded. It can take a few minutes to show up in-game.");
        }
        catch (error) {
            this.warn(`Could not set the image: ${error?.message ?? error}`);
        }
    }
}
exports.default = Showcase;
