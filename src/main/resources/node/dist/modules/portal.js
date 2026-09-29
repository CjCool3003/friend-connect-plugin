"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const bedrock_portal_1 = require("bedrock-portal");
const prismarine_auth_1 = require("prismarine-auth");
const fileManager_1 = require("../utils/fileManager");
const showcase_1 = require("./showcase");
const JOINABILITY = {
    invite_only: bedrock_portal_1.Joinability.InviteOnly,
    friends_only: bedrock_portal_1.Joinability.FriendsOnly,
    friends_of_friends: bedrock_portal_1.Joinability.FriendsOfFriends,
};
class Portal {
    static instance;
    static async Start() {
        const config = fileManager_1.default.Config();
        if (!config.ip || !config.port) {
            throw new Error("Missing IP or port in config");
        }
        const joinability = JOINABILITY[config.joinability];
        if (joinability === undefined) {
            throw new Error(`Invalid joinability "${config.joinability}" (use invite_only, friends_only or friends_of_friends)`);
        }
        this.instance = new bedrock_portal_1.BedrockPortal({
            host: {
                cache: "lib/account/",
                username: "account",
                options: {
                    flow: "live",
                    authTitle: prismarine_auth_1.Titles.MinecraftNintendoSwitch,
                },
                // Lines starting with "[FC-AUTH]" are picked up by the Paper plugin.
                onMsaCode: (code) => {
                    console.log(`[FC-AUTH] Open ${code.verification_uri} and enter the code ${code.user_code}`);
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
            this.instance.use(bedrock_portal_1.Modules.AutoFriendAccept, {});
        }
        if (config.autoAddFriends) {
            this.instance.use(bedrock_portal_1.Modules.AutoFriendAdd, {});
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
        console.log(`[FC-READY] Friend Connect started: ${this.instance.host.profile?.gamertag}`);
        // Optional custom account image. Runs in the background and never throws.
        const xuid = this.instance.host.profile?.xuid;
        if (config.imagePath && xuid) {
            void showcase_1.default.Apply(this.instance.host.authflow, xuid, config.imagePath, "lib/showcase.json");
        }
    }
}
exports.default = Portal;
