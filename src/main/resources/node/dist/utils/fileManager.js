"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const fs = require("fs");
class FileManager {
    // The Paper plugin passes the config path as the first argument.
    // Running the script by hand still works: it falls back to lib/config.json.
    static Config() {
        const path = process.argv[2] ?? "lib/config.json";
        return JSON.parse(fs.readFileSync(path, "utf8"));
    }
}
exports.default = FileManager;
