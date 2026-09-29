export interface Config {
  ip: string;
  port: number;
  hostName: string;
  levelName: string;
  joinability: "invite_only" | "friends_only" | "friends_of_friends";
  autoAcceptFriends: boolean;
  autoAddFriends: boolean;
  updatePresence: boolean;
  worldVersion: string;
  maxPlayers: number;
}
