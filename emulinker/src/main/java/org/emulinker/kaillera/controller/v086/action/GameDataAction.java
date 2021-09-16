package org.emulinker.kaillera.controller.v086.action;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.emulinker.kaillera.controller.messaging.MessageFormatException;
import org.emulinker.kaillera.controller.v086.V086Controller;
import org.emulinker.kaillera.controller.v086.protocol.*;
import org.emulinker.kaillera.model.KailleraUser;
import org.emulinker.kaillera.model.event.*;
import org.emulinker.kaillera.model.exception.GameDataException;

public class GameDataAction implements V086Action, V086GameEventHandler {
  private static Log log = LogFactory.getLog(GameDataAction.class);
  private static final String desc = "GameDataAction";
  private static GameDataAction singleton = new GameDataAction();

  public static GameDataAction getInstance() {
    return singleton;
  }

  private int actionCount = 0;
  private int handledCount = 0;

  private GameDataAction() {}

  @Override
  public int getActionPerformedCount() {
    return actionCount;
  }

  @Override
  public int getHandledEventCount() {
    return handledCount;
  }

  @Override
  public String toString() {
    return desc;
  }

  @Override
  public void performAction(V086Message message, V086Controller.V086ClientHandler clientHandler)
      throws FatalActionException {
    try {
      KailleraUser user = clientHandler.getUser();
      byte[] data = ((GameData) message).gameData();

      clientHandler.getClientGameDataCache().add(data);
      user.addGameData(data);
    } catch (GameDataException e) {
      log.debug("Game data error: " + e.getMessage());

      if (e.hasResponse()) {
        try {
          clientHandler.send(
              GameData.create(clientHandler.getNextMessageNumber(), e.getResponse()));
        } catch (MessageFormatException e2) {
          log.error("Failed to contruct GameData message: " + e2.getMessage(), e2);
        }
      }
    }
  }

  @Override
  public void handleEvent(GameEvent event, V086Controller.V086ClientHandler clientHandler) {
    byte[] data = ((GameDataEvent) event).getData();
    int key = clientHandler.getServerGameDataCache().indexOf(data);

    if (key < 0) {
      clientHandler.getServerGameDataCache().add(data);

      try {
        clientHandler.send(GameData.create(clientHandler.getNextMessageNumber(), data));
      } catch (MessageFormatException e) {
        log.error("Failed to contruct GameData message: " + e.getMessage(), e);
      }
    } else {
      try {
        clientHandler.send(CachedGameData.create(clientHandler.getNextMessageNumber(), key));
      } catch (MessageFormatException e) {
        log.error("Failed to contruct CachedGameData message: " + e.getMessage(), e);
      }
    }
  }
}
