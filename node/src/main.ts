import Portal from "./modules/portal";

Portal.Start().catch((error) => {
  console.error(`[FC-ERROR] ${error?.message ?? error}`);
  process.exit(1);
});

// Let the Paper plugin stop us cleanly.
process.on("SIGTERM", () => process.exit(0));
process.on("SIGINT", () => process.exit(0));

// If the Minecraft server dies without cleaning up, the pipe from the plugin
// closes. Exit then, so no orphaned process keeps the Xbox session alive.
process.stdin.on("end", () => process.exit(0));
process.stdin.on("close", () => process.exit(0));
process.stdin.resume();
