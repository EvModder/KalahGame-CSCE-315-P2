package Main;
import java.util.List;
import AI.*;
import GUI.GUIManager.GameResult;
import GUI.Old_GUIManager;
import Main.MoveTimer.TimerListener;
import ServerUtils.*;
import ServerUtils.Connection.MessageReceiver;

public class Old_KalahGame implements MessageReceiver, TimerListener{
	private Board board;
	private Connection connection;
	private Old_GUIManager guiHandler;
	private Settings settings;
	private MoveTimer timer;
	private KalahPlayer ai;
	private int pieRuleChooser, timeLimit;
	
	private boolean waitingForInfo=true, waitingForReady=true,
					waitingForOK=true, waitingForYourMove=true,
					waitingForBegin, isServer, playAsAI, myTurn, gameOver;
	
	public Old_KalahGame(Old_GUIManager handler, Settings settings){
		this(handler, settings, null);
	}
	public Old_KalahGame(Old_GUIManager handler, Settings settings, Connection conn){
		guiHandler = handler;
		this.settings = settings;
		isServer = settings.getBoolean("is-server");
		
		timer = new MoveTimer();

		handler.closeMenuWindow();

		//We need to get a new connection
		if(conn == null || conn.isClosed()){
			if(isServer) handler.openWaitingWindow();
			
			//New thread because we might have to wait for a connection
			new Thread(){@Override public void run(){
				if(isServer) connection = new ServerSide(Old_KalahGame.this, settings);
				else{
					String host = handler.getHostWindow(settings.getString("last-host"));
					if(host == null){
						handler.openMenuWindow();
						return;
					}
					else{
						settings.set("last-host", host);
						connection = new ClientSide(Old_KalahGame.this, settings);
					}
				}
				
				handler.closeWaitingWindow();
				
				if(connection.isClosed()){
					handler.connectionErrorWindow();
					handler.openMenuWindow();
				}
				else{
					playGame();
				}
			}}.start();
		}
		else{
			connection = conn;
			connection.setReceiver(this);
			playGame();
		}
	}
	
	private void playGame(){
		playAsAI = settings.getBoolean("play-as-AI");
		
		if(isServer){//set up game, send INFO
			int houses = settings.getInt("holes-per-side");
			int seeds = settings.getInt("seeds-per-hole");
			timeLimit = settings.getInt("time-limit");
			String first = settings.getString("starting-player");
			String type = settings.getString("game-type");
			
			myTurn = !first.equals("F");

			//print INFO
			connection.println("WELCOME");
			StringBuilder builder = new StringBuilder("INFO ");
			
			if(type.equals("C")){
				houses = settings.getString("custom-board").split(" ").length;
			}
			board = new Board(houses, seeds);
			builder.append(houses).append(' ').append(seeds).append(' ')
				   .append(timeLimit).append(' ').append(first).append(' ').append(type.equals("C") ? "R" : type);
			
			if(type.equals("R")){
				board.randomizeSeeds();
				for(int i=0; i<board.numHouses; ++i){
					builder.append(' ').append(board.housesAndKalahs[i]);
				}
			}
			else if(type.equals("C")){
				String[] starting = settings.getString("custom-board").split(" ");
				for(int i=0; i<starting.length; ++i){
					if(!starting[i].matches("^\\d+$")){
						connection.close();
						guiHandler.openGameErrorWindow("Unable to load setting: custom-board");
						guiHandler.openMenuWindow();
						return;
					}
					board.housesAndKalahs[i] = Integer.parseInt(starting[i]);
					board.housesAndKalahs[i+board.numHouses+1] = board.housesAndKalahs[i];
					builder.append(' ').append(board.housesAndKalahs[i]);
				}
			}
			guiHandler.openBoardWindow(board.housesAndKalahs);
			if(playAsAI) ai = new DumbAI(board.getCopy()); //TODO: Select an AI to play with
			connection.println(builder.toString());

			while(waitingForReady) Thread.yield();//wait for client to be ready
			if(settings.getBoolean("use-BEGIN")) connection.println("BEGIN");
		}
		else{
			while(waitingForInfo) Thread.yield();//wait for INFO
			if(playAsAI) ai = new StrategicAI(board.getCopy()); //TODO: Select an AI to play with
			connection.println("READY");
			waitingForBegin = settings.getBoolean("use-BEGIN");
			while(waitingForBegin) Thread.yield();//wait for BEGIN
		}

//		System.out.println("Starting game!");
		pieRuleChooser = myTurn ? 2 : 1;

		while(board.gameNotOver() && !gameOver){
			if(myTurn){
				//Time my own move. If I timeout, make myself lose.
				timer.startTimer(this, timeLimit);
				
				StringBuilder message = new StringBuilder("");
				if(playAsAI){
					List<Integer> moves = ai.getMove();
					if(pieRuleChooser == 1 && (pieRuleChooser=0)==0 && moves.get(0) == -1){
						message.append("P");
						board.pieRule();
					}
					else{
						for(int move : moves){
							message.append(move+1);
							if(board.moveSeeds(move) == board.kalah1()) message.append(' ');
						}
					}
				}
				else{//if not AI
					if(pieRuleChooser == 1 && (pieRuleChooser=0)==0 && guiHandler.getPieRuleWindow()){
						message.append("P");
						board.pieRule();
					}
					else{//not pie rule
						boolean anotherMove = true;
						while(anotherMove && !gameOver){
							anotherMove = false;
							guiHandler.enableButtons();
							
							while(!guiHandler.hasMove() && !gameOver) Thread.yield();
							if(gameOver) break;
							int move = guiHandler.getMove();
							
							if(board.validMove(move)){
								message.append(move+1);
								if(board.moveSeeds(move) == board.kalah1() && board.gameNotOver()){
									message.append(' ');
									guiHandler.updateBoardWindow(board.housesAndKalahs);
									anotherMove = true;
								}
							}
							else anotherMove = true;
						}
						guiHandler.disableButtons();
					}
				}//player move
				timer.cancelTimer();//I have finished my move.
				guiHandler.updateBoardWindow(board.housesAndKalahs);
				if(gameOver) break;

				//send move to opponent
				myTurn = false;
				waitingForOK = true;
				connection.println(message.toString());
				while(waitingForOK && !gameOver) Thread.yield();//wait for opponent to confirm move
			}
			else{
				//Time the opponent's move, if they timeout make them lose.
				timer.startTimer(this, timeLimit);
				while(waitingForYourMove && !gameOver) Thread.yield();//wait for opponent
				waitingForYourMove = true;

				if(pieRuleChooser == 2) pieRuleChooser = 0;
				myTurn = true;
			}
		}
		
		if(!board.gameNotOver() && settings.getBoolean("count-leftovers")){
			board.collectLeftoverSeeds();
			guiHandler.updateBoardWindow(board.housesAndKalahs);
		}
		
		//if I am the server, send results (end the game naturally)
		if(isServer && !gameOver){
			int score = board.getScoreDifference();

			if(score > 0) endTheGame(GameResult.WON);
			else if(score == 0) endTheGame(GameResult.TIED);
			else endTheGame(GameResult.LOST);
		}
		else{
			while(!gameOver) Thread.yield();//wait for results
		}
//		System.out.println("Score1 = "+board.housesAndKalahs[board.numHouses]);
//		System.out.println("Score2 = "+board.housesAndKalahs[board.numHouses*2+1]);

		System.out.println("Closing the game");

		guiHandler.closeBoardWindow();
		guiHandler.openMenuWindow();
	}

	@Override public void timerEnded(){
		if(myTurn){//I timed out
			if(isServer) endTheGame(GameResult.LOST);
			else /* hey, we timed out but the server hasn't noticed... :)*/;
		}
		else{//They timed out!
			if(isServer) endTheGame(GameResult.TIME);
			else /* im just a client. the server is cheating and i cant do anything :(*/;
		}
	}

	@Override public void timeElapsed(long time){
		guiHandler.updateBoardTimer(time);
	}
	
	public boolean isGameOver(){
		return gameOver;
	}
	
	void endTheGame(GameResult result){
		switch(result){
		case WON:
			connection.println("LOSER");
			break;
		case LOST:
			connection.println("WINNER");
			break;
		case TIED:
			connection.println("TIE");
			break;
		case TIME:
			connection.println("TIME\nLOSER");
			result = GameResult.WON;
			break;
		case ILLEGAL:
			connection.println("ILLEGAL\nLOSER");
			result = GameResult.WON;
			break;
		}
		connection.close();
		guiHandler.openGameOverWindow(result);
		gameOver = true;
	}
	
	//---------- I/O ----------------------------------------------------
	boolean parseServerMessage(String... args){
		GameResult result = null;

		if(args[0].equals("WELCOME")){
			//I have been welcomed to the server!
		}
		else if(args[0].equals("INFO")){
			board = new Board(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
			timeLimit = Integer.parseInt(args[3]);
			myTurn = args[4].equals("F");
			if(args[5].equals("R") || args[5].equals("C")){
				for(int i=0; i<board.numHouses; ++i){
					int seeds = Integer.parseInt(args[i+6]);
					board.housesAndKalahs[i] = seeds;
					board.housesAndKalahs[i+board.numHouses+1] = seeds;
				}
			}
			guiHandler.openBoardWindow(board.housesAndKalahs);
			waitingForInfo = false;
		}
		else if(args[0].equals("BEGIN")){
			waitingForBegin = false;
		}
		else if(args[0].equals("LOSER")){
			result = GameResult.LOST;
		}
		else if(args[0].equals("WINNER")){
			result = GameResult.WON;
		}
		else if(args[0].equals("TIE")){
			result = GameResult.TIED;
		}
		else if(args[0].equals("TIME")){
			result = GameResult.TIME;
		}
		else if(args[0].equals("ILLEGAL")){
			//wth server! I was playing fair :(
			result = waitingForOK ? GameResult.ILLEGAL : GameResult.WON;
		}
		else return false;

		if(result != null){
			connection.close();
			if(result != GameResult.TIME && result != GameResult.ILLEGAL
					&& settings.getBoolean("count-leftovers")){
				board.collectLeftoverSeeds();
				guiHandler.updateBoardWindow(board.housesAndKalahs);
			}
			guiHandler.openGameOverWindow(result);
			gameOver = true;
		}
		return true;
	}

	boolean parseClientMessage(String... args){
		if(args[0].equals("READY")){
			waitingForReady = false;
		}
		else return false;
		return true;
	}

	@Override public void receiveMessage(String message) {
		if(gameOver) return;

		String[] args = message.split(" ");

		if(args[0].matches("^\\d+$")){
			timer.cancelTimer();
			int land = board.kalah2();
			for(String str : args){
				int move = Integer.parseInt(str)+board.numHouses;
				if(!board.validMove(move) || land != board.kalah2()){
					//They moved more times than they should have
					if(isServer) endTheGame(GameResult.ILLEGAL);
					else System.out.println("Server made an illegal move!");
					return;
				}
				land = board.moveSeeds(move);
				if(playAsAI) ai.applyOpponentMove(move);
			}
			if(land == board.kalah2() && board.gameNotOver()){
				//They stopped sending moves sooner they should have
				if(isServer) endTheGame(GameResult.ILLEGAL);
				else System.out.println("Server made an illegal non-move!");
				return;
			}
			guiHandler.updateBoardWindow(board.housesAndKalahs);
			connection.println("OK");
			waitingForYourMove = false;
		}
		else if(args[0].equals("P")){
			timer.cancelTimer();
			if(pieRuleChooser == 2){
				board.pieRule();
				if(playAsAI) ai.applyOpponentMove(-1);
				guiHandler.updateBoardWindow(board.housesAndKalahs);
				
				connection.println("OK");
				waitingForYourMove = false;
			}
			else if(isServer) endTheGame(GameResult.ILLEGAL);
			else System.out.println("Server made an illegal pie rule move!");
		}
		else if(args[0].equals("OK")){
			waitingForOK = false;
		}
		else if(isServer && !parseClientMessage(args)){
			endTheGame(GameResult.ILLEGAL);
		}
		else if(!isServer && !parseServerMessage(args)){
			System.out.println("Unable to parse message from server!");
		}
	}
}