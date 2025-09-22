package wzd.sync;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;

import net.runelite.api.GameState;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyService;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.chat.ChatColorType;
import net.runelite.api.widgets.Widget;

@Slf4j
@PluginDescriptor(
        name = "ToB Party Sync",
        description = "Automatically manages Party groups when joining a Theatre of Blood team",
        tags = {"tob", "party", "partyhub", "automation", "theatre of blood"}
)
public class ToBPartyHubPlugin extends Plugin {
    private static final int TOB_VARBIT = 6440; // Generic ToB-related varbit
    private static final int PARTY_VARBIT = 6441; // Party-related varbit

    @Inject
    private Client client;

    @Inject
    private PartyService partyService;

    @Inject
    private ToBPartyHubPluginConfig config;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ChatMessageManager chatMessageManager;

    private String currentGroupName;
    private boolean isInToB = false;
    private int lastToBState = -1;
    private int lastPartyState = -1;
    private String detectedPartyLeader;
    private long lastPartyCheck = 0;
    private String previousPartyLeader;
    private boolean waitingForToBHudUpdate = false;

    private int tickCounter = 0;
    private boolean isTeamRefresh = false;

    @Provides
    ToBPartyHubPluginConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ToBPartyHubPluginConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        log.info("ToB Party Sync plugin started");
        currentGroupName = null;
        isInToB = false;
        lastToBState = -1;
        lastPartyState = -1;

        clientThread.invoke(this::checkToBStatus);
    }

    @Override
    protected void shutDown() throws Exception {
        log.info("ToB Party Sync plugin stopped");
        if (currentGroupName != null && isSyncPartyHub(currentGroupName)) {
            leaveCurrentGroup();
        }
    }

    /**
     * Monitor varbit changes to detect ToB party changes
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        // Only run the party leader check loop while in ToB
        if (!isInToB) {
            tickCounter = 0; // Reset when not in ToB
            return;
        }

        tickCounter++;

        if (tickCounter >= 27 && tickCounter < 30) {
            int ticksLeft = 30 - tickCounter;
            log.info("Checking party change in {} ticks", ticksLeft);
        }

        if (tickCounter >= 30) {
            tickCounter = 0;

            log.info("=== 30-TICK PARTY LEADER CHECK ===");
            String result = getCurrentPartyId(true);

            if (result != null && result.startsWith("LEADER_CHANGED:")) {
                String newLeaderName = result.substring("LEADER_CHANGED:".length());
                log.info("Leader change detected via tick loop - everyone should join new party hub for: {}", newLeaderName);
                handleToBPartyChange();
            } else if (result != null) {
                log.info("Party leader check complete - leader '{}' unchanged, no action needed", result);
                // Don't call handleToBPartyChange() when leader hasn't changed
            } else {
                log.info("No party leader detected in tick loop (ToB HUD empty or not ready)");
            }
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        // Check if ToB or party-related varbits changed
        if (event.getVarbitId() == TOB_VARBIT || event.getVarbitId() == PARTY_VARBIT) {
            boolean wasInToB = isInToB;
            checkToBStatus();

            // If we just entered ToB or party state changed
            if (isInToB && (!wasInToB || hasPartyStateChanged())) {
                log.debug("ToB party change detected via varbit {}", event.getVarbitId());

                // Mark that we're waiting for ToB HUD to update
                waitingForToBHudUpdate = true;
                lastPartyCheck = System.currentTimeMillis();

                // Start monitoring the ToB HUD interface AND immediately check for party hub
                log.info("Started ToB party monitoring - checking immediately + every 30 ticks");

                // Immediate check for instant party hub join
                clientThread.invokeLater(() -> {
                    log.info("Immediate party check after ToB entry");
                    handleToBPartyChange();
                });
            } else if (!isInToB && wasInToB) {
                // Left ToB - handle leaving based on party type and settings
                log.debug("Left ToB");

                if (config.autoLeaveWhenExitingToB() && currentGroupName != null) {
                    if (isSyncPartyHub(currentGroupName)) {
                        // Always leave sync party hubs when exiting ToB
                        log.info("Leaving sync party hub '{}' after exiting ToB", currentGroupName);
                        leaveCurrentGroup();
                    } else {
                        // Non-sync party hub - only leave if force join is enabled
                        if (config.forceJoinMode()) {
                            log.info("Leaving non-sync party hub '{}' after exiting ToB (force join enabled)", currentGroupName);
                            leaveCurrentGroup();
                        } else {
                            log.info("Staying in non-sync party hub '{}' after exiting ToB (force join disabled)", currentGroupName);
                        }
                    }
                }

                // Clear cached party leader when leaving ToB
                detectedPartyLeader = null;
                lastPartyCheck = 0;
            }
        }

        // Handle any party-related varbit changes while in ToB
        if (isInToB && event.getVarbitId() == PARTY_VARBIT) {
            log.info("VarbitChanged: Party varbit changed while in ToB - checking for immediate update");
            clientThread.invokeLater(() -> {
                log.info("Party varbit change - immediate party check");
                handleToBPartyChange();
            });
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN ||
                event.getGameState() == GameState.HOPPING) {
            // Reset state on logout/world hop
            lastToBState = -1;
            lastPartyState = -1;
            currentGroupName = null;
        }
    }

    private void sendGameMessage(String message) {
        if (!config.enableChatMessages()) {
            return;
        }

        final String formattedMessage = new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append(message)
                .append(".")
                .build();

        chatMessageManager.queue(QueuedMessage.builder()
                .type(net.runelite.api.ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(formattedMessage)
                .build());
    }

    /**
     * Check if we're currently in Theatre of Blood based on varbits
     */
    private void checkToBStatus() {
        try {
            int tobState = client.getVarbitValue(TOB_VARBIT);
            int partyState = client.getVarbitValue(PARTY_VARBIT);

            isInToB = tobState > 0 || partyState > 0;

            lastToBState = tobState;
            lastPartyState = partyState;

            log.debug("ToB status check - TOB varbit: {}, Party varbit: {}, In ToB: {}",
                    tobState, partyState, isInToB);
        } catch (Exception e) {
            log.warn("Error checking ToB status: {}", e.getMessage());
            isInToB = false;
        }
    }

    /**
     * Check if the party state has changed
     */
    private boolean hasPartyStateChanged() {
        try {
            int currentToBState = client.getVarbitValue(TOB_VARBIT);
            int currentPartyState = client.getVarbitValue(PARTY_VARBIT);

            return currentToBState != lastToBState || currentPartyState != lastPartyState;
        } catch (Exception e) {
            log.warn("Error checking party state: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the party leader from the in-game party interface
     */
    private String getPartyLeaderFromInterface() {
        // Use the same ToB HUD detection as getCurrentPartyId()
        try {
            Widget tobHudNames = client.getWidget(InterfaceID.TobHud.NAMES);

            if (tobHudNames != null && !tobHudNames.isHidden() && tobHudNames.getText() != null) {
                String namesText = tobHudNames.getText().trim();
                if (!namesText.isEmpty() && !namesText.equals("-<br>-<br>-<br>-<br>-")) {
                    String[] playerNames = namesText.split("<br>");
                    if (playerNames.length > 0) {
                        String leaderName = playerNames[0].trim();
                        if (!leaderName.isEmpty() && !leaderName.equals("-") &&
                                leaderName.length() >= 3 && leaderName.length() <= 12) {
                            log.info("Party leader from ToB HUD interface: '{}'", leaderName);
                            return leaderName;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error getting party leader from ToB HUD: {}", e.getMessage());
        }

        log.info("No party leader found in ToB HUD interface");
        return null;
    }

    /**
     * Check if a party hub group name appears to be created by this sync plugin
     * Sync party hubs follow the pattern: [World][PlayerName] (e.g., 330WISEOLDMAN, 416JOHNCENA)
     */
    private boolean isSyncPartyHub(String groupName) {
        if (groupName == null || groupName.length() < 4) {
            return false;
        }
        return groupName.matches("^\\d{3,4}[A-Z0-9]+$");
    }

    private String getCurrentPartyId() {
        return getCurrentPartyId(false);
    }

    private String getCurrentPartyId(boolean forceRefresh) {
        try {
            long currentTime = System.currentTimeMillis();

            // If we're waiting for ToB HUD to update but not forcing refresh, return cached
            if (waitingForToBHudUpdate && !forceRefresh) {
                log.debug("Waiting for ToB HUD update (use force refresh to check now)");
                return detectedPartyLeader != null && !detectedPartyLeader.equals("-") ? detectedPartyLeader : null;
            }

            // Check party leader every 10 seconds or if forced refresh
            if (!forceRefresh && currentTime - lastPartyCheck < 10000 && detectedPartyLeader != null && !waitingForToBHudUpdate) {
                log.debug("Using cached party leader: '{}'", detectedPartyLeader);
                return detectedPartyLeader.equals("-") ? null : detectedPartyLeader;
            }

            log.info("=== PARTY DETECTION DEBUG ===");

            Widget tobHudNames = client.getWidget(InterfaceID.TobHud.NAMES);

            if (tobHudNames != null && !tobHudNames.isHidden() && tobHudNames.getText() != null) {
                String namesText = tobHudNames.getText().trim();
                log.info("ToB HUD names text: '{}'", namesText);

                if (!namesText.isEmpty() && !namesText.equals("-<br>-<br>-<br>-<br>-")) {
                    String[] playerNames = namesText.split("<br>");

                    if (playerNames.length > 0) {
                        String leaderName = playerNames[0].trim();

                        // Check if leader has changed
                        boolean leaderChanged = !leaderName.equals(detectedPartyLeader);

                        // Update cache
                        lastPartyCheck = currentTime;
                        previousPartyLeader = detectedPartyLeader; // Store current as previous before updating
                        detectedPartyLeader = leaderName;
                        waitingForToBHudUpdate = false;

                        if (!leaderName.isEmpty() && !leaderName.equals("-") &&
                                leaderName.length() >= 3 && leaderName.length() <= 12) {
                            log.info("ToB party leader detected: '{}' (changed: {})", leaderName, leaderChanged);

                            // If leader changed, announce team refresh
                            if (leaderChanged && previousPartyLeader != null && !previousPartyLeader.equals("-")) {
                                sendGameMessage("Team refreshed - new leader: " + leaderName);
                                log.info("Team leader changed from '{}' to '{}'", previousPartyLeader, leaderName);
                                // Force party hub update since leader changed
                                isTeamRefresh = true; // Mark as team refresh to suppress join/leave messages
                                return "LEADER_CHANGED:" + leaderName;
                            }

                            // Get current world to build expected party hub name
                            String world = String.valueOf(client.getWorld());
                            String expectedSyncPartyHub = world + leaderName.toUpperCase();

                            log.info("Expected sync party hub would be: '{}'", expectedSyncPartyHub);

                            // If this raid team would create a sync party hub, allow it
                            if (isSyncPartyHub(expectedSyncPartyHub)) {
                                log.info("Raid team would create sync party - allowing auto-join");

                                // For tick loop monitoring, still return the leader name for change detection
                                if (forceRefresh) {
                                    return leaderName; // Return leader for change detection
                                }
                                return null; // Allow sync party creation/switching for normal calls
                            } else {
                                // Non-sync leader name means user is probably in a custom party hub
                                log.info("Non-sync raid leader detected - assuming user is in custom party hub");
                                return leaderName; // Return leader name as party identifier
                            }
                        } else if (leaderName.equals("-")) {
                            log.info("No party leader (empty slot)");
                            detectedPartyLeader = "-";
                            return null;
                        } else {
                            log.warn("Invalid leader name: '{}'", leaderName);
                            return null;
                        }
                    } else {
                        log.warn("No player names found in ToB HUD text");
                    }
                } else {
                    log.info("ToB HUD shows empty team or still loading");
                    // Don't update cache if still loading
                    return detectedPartyLeader != null && !detectedPartyLeader.equals("-") ? detectedPartyLeader : null;
                }
            } else {
                log.info("ToB HUD names widget not found, hidden, or has no text");
            }

            // Update cache even if no party found
            lastPartyCheck = currentTime;
            detectedPartyLeader = null;
            waitingForToBHudUpdate = false;

            log.info("=== NO PARTY DETECTED ===");
            return null;
        } catch (Exception e) {
            log.error("Error getting current party ID: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Handle ToB party changes by creating/joining appropriate PartyHub group
     */
    private void handleToBPartyChange() {

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getName() == null) {
            log.warn("Cannot create Party group - local player is null");
            return;
        }

        String world = String.valueOf(client.getWorld());

        if (world.equals("0") || world.equals("-1")) {
            log.warn("Cannot create Party group - invalid world: {}", world);
            return;
        }

        String actualCurrentParty = getCurrentPartyId();

        log.info("=== PARTY STATE DEBUG ===");
        log.info("currentGroupName (tracked): '{}'", currentGroupName);
        log.info("actualCurrentParty (detected): '{}'", actualCurrentParty);
        log.info("forceJoinMode: {}", config.forceJoinMode());

        // Handle leader change case - always join new party when leader changes
        if (actualCurrentParty != null && actualCurrentParty.startsWith("LEADER_CHANGED:")) {
            String newLeaderName = actualCurrentParty.substring("LEADER_CHANGED:".length());
            log.info("Leader changed to '{}' - forcing party hub update for everyone", newLeaderName);
            // Continue with normal flow to join new party hub
            actualCurrentParty = null; // Allow joining regardless of current party
        }

        // Use actual party if available, otherwise fall back to tracked
        String currentParty = (actualCurrentParty != null) ? actualCurrentParty : currentGroupName;

        // Update our tracking if we detected a party we weren't tracking
        if (actualCurrentParty != null && !actualCurrentParty.equals(currentGroupName)) {
            log.info("Updating party tracking: '{}' -> '{}'", currentGroupName, actualCurrentParty);
            currentGroupName = actualCurrentParty;
        }

        log.info("Final party to check: '{}'", currentParty);
        log.info("=== END DEBUG ===");

        // SIMPLE LOGIC: Block if in non-sync party and force join disabled
        if (!config.forceJoinMode() && currentParty != null && !isSyncPartyHub(currentParty)) {
            String partyLeader = getPartyLeaderFromInterface();
            String leaderName = (partyLeader != null) ? partyLeader : localPlayer.getName();
            String suggestedGroupName = world + leaderName.toUpperCase();

            sendGameMessage("You are in non-sync party hub '" + currentParty +
                    "'. To join raid team party hub \"" + suggestedGroupName +
                    "\", enable force join in settings or manually join the group");
            log.info("BLOCKED - in non-sync party: '{}'", currentParty);
            return;
        }

        log.info("Proceeding with auto-join");

        // Try to get party leader from the party interface
        String partyLeader = getPartyLeaderFromInterface();

        // If no party leader found, use local player as fallback
        String leaderName;
        if (partyLeader != null) {
            leaderName = partyLeader;
            log.info("Using party leader '{}' for group name", leaderName);
        } else {
            leaderName = localPlayer.getName();
            log.info("No party leader found, using local player '{}' for group name", leaderName);
        }

        String newGroupName = world + leaderName.toUpperCase();

        // Don't recreate the same group
        if (currentGroupName != null && currentGroupName.equalsIgnoreCase(newGroupName)) {
            log.debug("Already in correct Party group: {}", currentGroupName);
            return;
        }

        log.info("Creating new Party group: {}", newGroupName);

        // Leave old group if in one
        if (currentGroupName != null) {
            log.debug("Leaving previous Party group: {}", currentGroupName);
            leaveCurrentGroup();
        }

        // Join/create new group using PartyService
        try {
            // Add a small delay to prevent rapid switching issues
            Thread.sleep(100);

            partyService.changeParty(newGroupName);
            currentGroupName = newGroupName;
            log.info("Successfully joined Party group: {}", newGroupName);

            // Only show message if not during team refresh
            if (!isTeamRefresh) {
                sendGameMessage("You have joined party hub " + newGroupName);
            }

            // Reset team refresh flag
            isTeamRefresh = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrupted while joining party: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to change to Party group {}: {}", newGroupName, e.getMessage());
        }
    }

    private void leaveCurrentGroup() {
        if (currentGroupName != null) {
            log.info("Leaving Party group: {}", currentGroupName);
            try {
                partyService.changeParty(null);

                // Only show message if not during team refresh
                if (!isTeamRefresh) {
                    sendGameMessage("You have left the party");
                }
                currentGroupName = null;
            } catch (Exception e) {
                log.error("Failed to leave Party group: {}", e.getMessage());
                currentGroupName = null;
            }
        }
    }

}