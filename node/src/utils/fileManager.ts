import * as fs from "fs";
import type { Config } from "../types/fileManager";

export default class FileManager {
  // The Paper plugin passes the config path as the first argument.
  // Running the script by hand still works: it falls back to lib/config.json.
  public static Config(): Config {
    const path = process.argv[2] ?? "lib/config.json";
    return JSON.parse(fs.readFileSync(path, "utf8"));
  }
}
