import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

class BoardFrame extends JFrame{
	private static final long serialVersionUID = 1L;
	
	//index [0] is leftmost house of player 1
	HouseButton[] housesAndKalahs;
	int numHouses;
	
	BoardFrame(int numHouses, int numSeeds){
		this.numHouses = numHouses;
		housesAndKalahs = new HouseButton[numHouses*2+2];
		
		JPanel player1Houses = new JPanel(new GridLayout());
		JPanel player2Houses = new JPanel(new GridLayout());
		
		for(int i=0; i<numHouses; ++i){
			player1Houses.add(housesAndKalahs[i] = new HouseButton(this, i, numSeeds));
		}
		
		housesAndKalahs[numHouses] = new HouseButton(this, numHouses, 0);//kalah1
		int kalah2 = housesAndKalahs.length-1;
		housesAndKalahs[kalah2] = new HouseButton(this, kalah2, 0);//kalah2
		
		for(int i=kalah2-1; i>numHouses; --i){
			player2Houses.add(housesAndKalahs[i] = new HouseButton(this, i, numSeeds));
		}
		
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(player2Houses);
		panel.add(player1Houses);
		setPreferredSize(new Dimension(800, 200));
		setMinimumSize(new Dimension(150+20*this.numHouses, 100));
		setTitle("Timer: 5:00");//setTitle("Kalah Board");
		add(panel);
//		add(new JLabel("Timer: 5:00", SwingConstants.CENTER), BorderLayout.SOUTH);
		add(housesAndKalahs[kalah2], BorderLayout.WEST);
		add(housesAndKalahs[numHouses], BorderLayout.EAST);
		pack();
		setLocationRelativeTo(null);
		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
//		setIconImage(KalahClient.clientImage);
//		setVisible(true);
	}
	
	int moveSeeds(int from){
		int numSeeds = housesAndKalahs[from].getSeeds();
		boolean player1 = from < numHouses;
		
		//disable this button, squares with 0 seeds can't be clicked.
		housesAndKalahs[from].setEnabled(false);
		housesAndKalahs[from].setSeeds(0);
		
		int i = from;
		while(numSeeds > 0){
			if(++i == housesAndKalahs.length) i = 0;
			
			if(player1){
				if(i == housesAndKalahs.length-1) continue;
			}
			else{
				if(i == numHouses) continue;
			}
			--numSeeds;
			
			//if this square was previously 0, enable it now that it is >0.
			if(i < numHouses && housesAndKalahs[i].getSeeds() == 0){
				housesAndKalahs[i].setEnabled(true);
			}
			housesAndKalahs[i].addSeeds(1);
		}
		
		//capture pieces on the opposite square
		if(housesAndKalahs[i].getSeeds() == 1 && (
				(player1 && i < numHouses) ||
				(!player1 && i > numHouses && i < housesAndKalahs.length-1)
		)){
			int capture = i + (numHouses - i) * 2;
			
			int seeds = housesAndKalahs[i].getSeeds() + housesAndKalahs[capture].getSeeds();
			
			if(player1) housesAndKalahs[numHouses].addSeeds(seeds);
			else housesAndKalahs[numHouses*2+1].addSeeds(seeds);
			
			housesAndKalahs[i].setSeeds(0);
			housesAndKalahs[capture].setSeeds(0);
		}
		return i;
	}
	
	void enableButtons(){
		for(int i=0; i<numHouses; ++i)
			if(housesAndKalahs[i].getSeeds() > 0)
				housesAndKalahs[i].setEnabled(true);
	}
	void disableButtons(){
		for(int i=0; i<numHouses; ++i)
			housesAndKalahs[i].setEnabled(false);
	}
	
	void collectLeftoverSeeds(){
		for(int i=0; i<numHouses; ++i){
			housesAndKalahs[numHouses].addSeeds(housesAndKalahs[i].getSeeds());
		}
		for(int i=numHouses+1; i<housesAndKalahs.length; ++i){
			housesAndKalahs[housesAndKalahs.length-1].addSeeds(housesAndKalahs[i].getSeeds());
		}
	}
	
	boolean isWinning(){
		return housesAndKalahs[numHouses].getSeeds() >
			   housesAndKalahs[housesAndKalahs.length-1].getSeeds();
	}
	
	boolean validMove(int i){
		return (housesAndKalahs[i].getSeeds() != 0 && i != numHouses && i != numHouses*2+1);
	}
}
/*
   * * * 9 8 7
13              6
   0 1 2 3 4 5

*/