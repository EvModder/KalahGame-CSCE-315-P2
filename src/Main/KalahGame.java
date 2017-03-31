package Main;
import AI.*;
import ServerUtils.*;
import ServerUtils.Connection.MessageReceiver;
import Main.Utils.*;

public class KalahGame implements MessageReceiver, TimerListener{
	private BoardFrame board;
	public static int move;
	public static boolean waitingForMove=true;
	
	Connection connection;
	boolean waitingForInfo=true, waitingForReady=true, waitingForOK=true, waitingForYourMove=true;
	public boolean isServer, myTurn, gameOver, playAsAI;
	int pieRuleChooser, timeLimit;
	
	public KalahGame(boolean isServer){
		this(isServer, null);
	}
	public KalahGame(boolean isServer, Connection conn){
		this.isServer = isServer;
		playAsAI = Boolean.parseBoolean(Settings.getSetting("play-as-AI"));
		AI ai = new DumbAI();//TODO: select an AI to play with
		
		Utils.openWaitingWindow();
		Utils.closeMenuWindow();
		
		//Main game thread
		new Thread(){@Override public void run(){
			if(conn == null){
				connection = isServer ? new ServerSide(KalahGame.this) : new ClientSide(KalahGame.this);
			}
			else{
				connection = conn;
				connection.setReceiver(KalahGame.this);
				System.out.println("I am the receiver");
			}
			Utils.closeWaitingWindow();
			
			if(connection.isClosed()){
				Utils.connectionErrorWindow();
				Utils.openMenuWindow();
				return;
			}
			
			if(isServer){//set up game, send INFO
				int houses = Integer.parseInt(Settings.getSetting("holes-per-side"));
				int seeds = Integer.parseInt(Settings.getSetting("seeds-per-hole"));
				timeLimit = Integer.parseInt(Settings.getSetting("time-limit"));
				String first = Settings.getSetting("starting-player");
				String type = Settings.getSetting("game-type");
				board = new BoardFrame(houses, seeds);
				myTurn = !first.equals("F");
				
				//print INFO
				connection.println("WELCOME");
				StringBuilder builder = new StringBuilder("INFO ").append(houses).append(' ')
						.append(seeds).append(' ')
						.append(timeLimit).append(' ')
						.append(first).append(' ').append(type);
				
				if(type.equals("R")){
					//randomize board, then send it to client
					board.randomizeSeeds();
					for(int i=0; i<board.numHouses; ++i){
						builder.append(' ').append(board.housesAndKalahs[i].getSeeds());
					}
				}
				
				connection.println(builder.toString());
				
				while(waitingForReady) yield();//wait for client to be ready
			}
			else{
				while(waitingForInfo) yield();//wait for INFO
				connection.println("READY");
			}
			board.setVisible(true);
			
			System.out.println("Starting game!");
			pieRuleChooser = myTurn ? 2 : 1;
			
			while(board.gameNotOver() && !gameOver){
				//if it is my turn
				if(myTurn){
					//Time my own move. If I timeout, make myself lose.
//					System.out.println("Waiting for myself to move");
					Utils.startTimer(KalahGame.this, timeLimit);
					
					//if I get to choose pie rule
					if(pieRuleChooser == 1){
						pieRuleChooser = 0;
						if((!playAsAI && Utils.getPieRuleWindow()) ||
							(playAsAI && ai.doPieRule(board.getSquaresAsInts(), timeLimit)))
						{
							Utils.cancelTimer();//I have decided my move
							connection.println("P");
							board.pieRule();
							
							//wait for opponent to confirm move
							while(waitingForOK && !gameOver) yield();
							waitingForOK = true;
							myTurn = false;
							continue;
						}
					}
					
					StringBuilder message = new StringBuilder("");
					if(playAsAI){//get ai move
						for(int move : ai.getMove(board.getSquaresAsInts(), timeLimit)){
							message.append(move+1);
							if(board.moveSeeds(move) == board.numHouses) message.append(' ');
						}
					}
					else{//get player move
						board.enableButtons();
						//wait for this player to move, make moves as long as they hit their Kalah
						while(waitingForMove && !gameOver){
							while(waitingForMove && !gameOver) yield();
							if(!board.validMove(move)){
								waitingForMove = true;
								continue;
							}
							message.append(move+1);
							if(board.moveSeeds(move) == board.numHouses && board.gameNotOver()){
								message.append(' ');
								waitingForMove = true;
							}
						}
						waitingForMove = true;
						board.disableButtons();
					}
					Utils.cancelTimer();//I have finished my move.
					
					//send move to opponent
					connection.println(message.toString());
					myTurn = false;
					
					Utils.startTimer(KalahGame.this, timeLimit);//TODO: REMOVE THIS LINE IN SUBMISSION
					while(waitingForOK && !gameOver) yield();//wait for opponent to confirm move
					waitingForOK = true;
					Utils.cancelTimer();//TODO: REMOVE THIS LINE IN SUBMISSION
				}
				else{
					//Time the opponent's move, if they timeout make them lose.
					//System.out.println("Waiting for opponent to move");
					Utils.startTimer(KalahGame.this, timeLimit);
					while(waitingForYourMove && !gameOver) yield();//wait for opponent
					Utils.cancelTimer();
					
					waitingForYourMove = true;
					if(pieRuleChooser == 2) pieRuleChooser = 0;
					myTurn = true;
				}
			}
			//if I am the server and the game needs to be ended, end it naturally
			if(isServer && !gameOver){
				if(Boolean.parseBoolean(Settings.getSetting("count-leftovers"))){
					board.collectLeftoverSeeds();
				}
				int score = board.getScoreDifference();
				
				GameResult result;
				if(score > 0){
					result = GameResult.WON;
					endTheGame(GameResult.LOST);
				}
				else if(score == 0){
					result = GameResult.TIED;
					endTheGame(GameResult.TIED);
				}
				else{
					result = GameResult.LOST;
					endTheGame(GameResult.WON);
				}
				Utils.openGameOverWindow(result);
			}
			else{
				while(!gameOver) yield();//wait for results
			}
//			System.out.println("Score1 = "+board.housesAndKalahs[board.numHouses]);
//			System.out.println("Score2 = "+board.housesAndKalahs[board.numHouses*2+1]);
			
			board.setVisible(false);
			board.dispose();
			Utils.openMenuWindow();
		}}.start();
	}
	
	@Override public void timerEnded(){
//		gameOver = true;//game always ends on timeout
		
		if(myTurn){//oops, they win... We timed out
			if(isServer){
				endTheGame(GameResult.WON);
				Utils.openGameOverWindow(GameResult.TIME);
			}
			//else /* hey, we timed out but the server hasn't noticed... :)*/;
		}
		else{//they timed out!
			if(isServer){
				endTheGame(GameResult.TIME);
				Utils.openGameOverWindow(GameResult.WON);
			}
			//else /* im just a client. the server is cheating and i cant do anything :(*/
		}
	}
	
	void endTheGame(GameResult result){
		switch(result){
		case WON:
			connection.println("WINNER");
			break;
		case LOST:
			connection.println("LOSER");
			break;
		case TIED:
			connection.println("TIE");
			break;
		case TIME:
			connection.println("TIME\nLOSER");
			break;
		case ILLEGAL:
			connection.println("ILLEGAL\nLOSER");
			break;
		}
		connection.close();
		gameOver = true;
	}
	
	
	//---------- I/O ----------------------------------------------------
	public boolean parseServerMessage(String... args){
		GameResult result = null;
		
		if(args[0].equals("WELCOME")){
			//I have been welcomed to the server!
		}
		else if(args[0].equals("INFO")){// INFO 4 1 5000 F S
			
			board = new BoardFrame(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
			timeLimit = Integer.parseInt(args[3]);
			myTurn = args[4].equals("F");
			if(args[5].equals("R")){
				for(int i=0; i<board.numHouses; ++i){
					int seeds = Integer.parseInt(args[i+6]);
					board.housesAndKalahs[i].setSeeds(seeds);
					board.housesAndKalahs[i+board.numHouses+1].setSeeds(seeds);
				}
			}
			waitingForInfo = false;
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
			result = GameResult.ILLEGAL;
		}
		else return false;
		
		if(result != null){
			connection.close();
			if(result != GameResult.TIME && Boolean.parseBoolean(
					Settings.getSetting("count-leftovers"))){
				board.collectLeftoverSeeds();
			}
			Utils.openGameOverWindow(result);
			gameOver = true;
		}
		return true;
	}
	
	public boolean parseClientMessage(String... args){
		if(args[0].equals("READY")){
			waitingForReady = false;
		}
		else return false;
		return true;
	}
	
	@Override
	public void receiveMessage(String message) {
		if(gameOver) return;
		
		String[] args = message.split(" ");
		
		if(args[0].matches("^\\d+$")){
			int land = board.housesAndKalahs.length-1;
			for(String str : args){
				int move = Integer.parseInt(str)+board.numHouses;
				if(!board.validMove(move) || land != board.housesAndKalahs.length-1){
					//They moved more times than they should have
					if(isServer) endTheGame(GameResult.ILLEGAL);
					else System.out.println("Server made an illegal move!");
				}
				land = board.moveSeeds(move);
			}
			if(land == board.housesAndKalahs.length-1 && board.gameNotOver()){
				//They stopped sending moves sooner they should have
				if(isServer) endTheGame(GameResult.ILLEGAL);
				else System.out.println("Server made an illegal non-move!");
				return;
			}
			waitingForYourMove = false;
			connection.println("OK");
		}
		else if(args[0].equals("P")){
			if(pieRuleChooser == 2){
				board.pieRule();
				waitingForYourMove = false;
				connection.println("OK");
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