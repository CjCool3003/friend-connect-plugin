import { BedrockPortal, Joinability, Modules } from "bedrock-portal";
import { Titles } from "prismarine-auth";
import FileManager from "../utils/fileManager";
import Showcase from "./showcase";

const JOINABILITY: Record<string, Joinability> = {
  invite_only: Joinability.InviteOnly,
  friends_only: Joinability.FriendsOnly,
  friends_of_friends: Joinability.FriendsOfFriends,
};

export default class Portal {
  private static instance: BedrockPortal;

  public static async Start(): Promise<void> {
    const config = FileManager.Config();

    if (!config.ip || !config.port) {
      throw new Error("Missing IP or port in config");
    }

    const joinability = JOINABILITY[config.joinability];

    if (joinability === undefined) {
      throw new Error(
        `Invalid joinability "${config.joinability}" (use invite_only, friends_only or friends_of_friends)`,
      );
    }

    this.instance = new BedrockPortal({
      host: {
        cache: "lib/account/",
        username: "account",
        options: {
          flow: "live",
          authTitle: Titles.MinecraftNintendoSwitch,
        },
        // Lines starting with "[FC-AUTH]" are picked up by the Paper plugin.
        onMsaCode: (code) => {
          console.log(
            `[FC-AUTH] Open ${code.verification_uri} and enter the code ${code.user_code}`,
          );
        },
      },
      world: {
        hostName: config.hostName,
        name: config.levelName,
        version: config.worldVersion,
        maxMemberCount: config.maxPlayers,
      },
      ip: config.ip,
      port: config.port,
      joinability,
      updatePresence: config.updatePresence,
    });

    if (config.autoAcceptFriends) {
      this.instance.use(Modules.AutoFriendAccept, {});
    }

    if (config.autoAddFriends) {
      this.instance.use(Modules.AutoFriendAdd, {});
    }

    this.instance.on("friendAdded", (player) => {
      console.log(`Friend added: ${player.profile?.gamertag}`);
    });
    this.instance.on("playerJoin", (player) => {
      console.log(`Player joined the portal: ${player.profile?.gamertag}`);
    });
    this.instance.on("playerLeave", (player) => {
      console.log(`Player redirected: ${player.profile?.gamertag}`);
    });

    await this.instance.start();

    console.log(
      `[FC-READY] Friend Connect started: ${this.instance.host.profile?.gamertag}`,
    );

    // Optional custom account image. Runs in the background and never throws.
    const xuid = this.instance.host.profile?.xuid;
    if (config.imagePath && xuid) {
      void Showcase.Apply(
        this.instance.host.authflow,
        xuid,
        config.imagePath,
        "lib/showcase.json",
      );
    }
  }
}
