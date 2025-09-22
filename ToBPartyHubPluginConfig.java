package wzd.sync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("tobpartyhub")
public interface ToBPartyHubPluginConfig extends Config
{
    @ConfigItem(
            keyName = "autoLeaveWhenExitingToB",
            name = "Auto-leave when exiting ToB",
            description = "Automatically leave sync PartyHub group when exiting Theatre of Blood"
    )
    default boolean autoLeaveWhenExitingToB()
    {
        return true;
    }



    @ConfigItem(
            keyName = "enableChatMessages",
            name = "Enable chat messages",
            description = "Show in-game chat messages when joining/leaving PartyHub groups"
    )
    default boolean enableChatMessages()
    {
        return true;
    }

    @ConfigItem(
            keyName = "forceJoinMode",
            name = "Force join mode",
            description = "When enabled, will join raid party hub even if already in a non-sync party hub"
    )
    default boolean forceJoinMode()
    {
        return true;
    }
}