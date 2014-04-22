package com.sillysoft.lux.agent;

import com.sillysoft.lux.*;
import com.sillysoft.lux.util.*;

import java.util.*;

public class LearningAgent implements LuxAgent
{
	// This agent's ownerCode:
	protected int ID;

	// We store some refs the board and to the country array
	protected Board board;
	protected Country[] countries;

	// values used in tactics analysis, adjusted via learning weights
	float recklessness;
	float recklessFortifyThreshold;
	float recklessCardThreshold;
	// learning weights
	float A, B, C, D, E, F, G, H, J, K, L;
	
	// It is useful to have a random number generator for a couple of things
	protected Random rand;

	public LearningAgent()
		{
		rand = new Random();
		}

	// Save references to 
	public void setPrefs( int newID, Board theboard )
		{
		ID = newID;		// this is how we distinguish what countries we own

		board = theboard;
		countries = board.getCountries();
		}

	public String name()
		{
		return "Learning Agent";
		}

	public float version()
		{
		return 1.0f;
		}

	public String description()
		{
		return "Learning Agent gets better the more games it plays";
		}

	// Possibly remove this and settle on random starting countries
	//This method currently tries to grab a continent at the start
	public int pickCountry()
		{
		int goalCont = BoardHelper.getSmallestPositiveEmptyCont(countries, board);

		if (goalCont == -1) // oops, there are no unowned conts
			goalCont = BoardHelper.getSmallestPositiveOpenCont(countries, board);

		// So now pick a country in the desired continent
		return pickCountryInContinent(goalCont);
		}


	// Picks a country in <cont>, starting with	countries that have neighbors that we own.
	// If there are none of those then pick the country with the fewest neighbors total.
	protected int pickCountryInContinent(int cont)
		{
		// Cycle through the continent looking for unowned countries that have neighbors we own
		CountryIterator continent = new ContinentIterator(cont, countries);
		while (continent.hasNext())
			{
			Country c = continent.next();
			if (c.getOwner() == -1 && c.getNumberPlayerNeighbors(ID) > 0)
				{
				// We found one, so pick it
				return c.getCode();
				}
			}

		// we neighbor none of them, so pick the open country with the fewest neighbors
		continent = new ContinentIterator(cont, countries);
		int bestCode = -1;
		int fewestNeib = 1000000;
		while (continent.hasNext())
			{
			Country c = continent.next();
			if (c.getOwner() == -1 && c.getNumberNeighbors() < fewestNeib)
				{
				bestCode = c.getCode();
				fewestNeib = c.getNumberNeighbors();
				}
			}

		if (bestCode == -1)
			{
			// We should never get here, so print an alert if we do
			System.out.println("ERROR in Angry.pickCountryInContinent() -> there are no open countries");
			}

		return bestCode;
		}


	// Treat initial armies the same as normal armies
	public void placeInitialArmies( int numberOfArmies )
		{
		placeArmies( numberOfArmies );
		}

	// Fill this method with strategy for cashing cards
	public void cardsPhase( Card[] cards )
		{
			Card[] set=null;
			if(cards.length==5 || recklessness>recklessCardThreshold)
			{
				set=Card.getBestSet(cards, ID, countries);
			}
			if(set!=null)
			{
				board.cashCards(set[0], set[1], set[2]);
			}
		}

	
	public void placeArmies( int numberOfArmies )
		{
		Country mostValuableCountry = null;
		float largestStrategicValue=-100000;
		// Use a PlayerIterator to cycle through all the countries that we own.
		CountryIterator own = new PlayerIterator( ID, countries );
		while(numberOfArmies>0)
		{
			while (own.hasNext()) 
			{
				Country us = own.next();
				float strategicValue=calculateStrategicValue(us);
				
				// If it's the best so far store it
				if ( strategicValue > largestStrategicValue )
				{
					largestStrategicValue=strategicValue;
					mostValuableCountry=us;
				}
			}
			board.placeArmies( 1, mostValuableCountry);
			numberOfArmies--;
			}
		}



	// During the attack phase, Angry has a clear goal:
//	 		Take over as much land as possible. 
	// Therefore he performs every available attack that he thinks he can win,
	// starting with the best matchups
	public void attackPhase()
	{
		//We choose a target and attack, then evaluate if we should continue attacking
		int countriesConquered=0;
		boolean stillAttacking=true;
		while(stillAttacking)
		{
			// Cycle through all of the countries that we have 4 or more armies on. 
			// It is never wise to attack with less than 4 armies (3 committed to attack)
			// We look through all owned countries that are able to attack,
			// and check the strategic value of of the neighboring enemy countries
			// The enemy country with the lowest Strategic value is selected as the attack target 
			// After checking all possible attacking countries 
			CountryIterator armies = new ArmiesIterator( ID, 4, countries );
			Country attacker=null;
			Country target=null;
			float lowestStrategicValue=1000000;
			while (armies.hasNext()) 
			{
				Country us = armies.next();
				int[] possibleTargets=us.getHostileAdjoiningCodeList();
				for(int i=0; i<possibleTargets.length; i++)
				{
					float strategicValue=calculateStrategicValue(countries[possibleTargets[i]]);
					//if target has low strategic value (should be taken)
					// and is plausible attack (can be taken), we set this as current preferred target
					if(strategicValue<lowestStrategicValue&&plausibleAttack(us,countries[possibleTargets[i]]))
					{
						lowestStrategicValue=strategicValue;
						attacker=us;
						target=countries[possibleTargets[i]];
					}
				}
			}
			//If target found
			if(target!=null)
			{
				board.attack(attacker, target, false);
				if(target.getOwner()==ID)
				{
					countriesConquered++;
				}
				stillAttacking=evaluateAttackPhase(countriesConquered);
			}
			else
			{
				stillAttacking=false;
			}
		}
	}
	 // End of attackPhase



	// When deciding how many armies to move in after a successful attack, 
	// Angry will just put them all into the country with more enemies
	public int moveArmiesIn( int cca, int ccd)
		{
		// If the defending country has no adjacent enemies we keep the maximum number of troops
		// possible in the attacking country
		if ( countries[ccd].getHostileAdjoiningCodeList().length>0 )
			return 0;

		// Otherwise we move everyone into the newly conquered country
		return countries[cca].getArmies()-1;
		}

	public void fortifyPhase()
	{	
		// Cycle through all the countries and find countries that we could move from:
		// if country has no surrounding enemies, move armies toward country with most strategic value
		// otherwise check recklessness to decide how to move armies
		CountryIterator armies = new ArmiesIterator( ID, 2, countries );
		
		while(armies.hasNext())
		{
			Country us=armies.next();
			int[] adjoiningCountries = us.getFriendlyAdjoiningCodeList();
			Country fortifyTarget=null;
			// if reckless, move to attack position
			if(recklessness>recklessFortifyThreshold)
			{
				float highestStrategicValue=calculateStrategicValue(us);
				for(int i=0; i<adjoiningCountries.length;i++)
				{
					float strategicValue=calculateStrategicValue(countries[adjoiningCountries[i]]);
					if( strategicValue > highestStrategicValue)
					{
						fortifyTarget=countries[adjoiningCountries[i]];
						highestStrategicValue=strategicValue;
					}
				}	
			}
			// else move to defend
			else
			{
				float highestVulnerability=calculateVulnerability(us);
				for(int i=0; i<adjoiningCountries.length;i++)
				{
					float vulnerability=calculateVulnerability(countries[adjoiningCountries[i]]);
					if(vulnerability>highestVulnerability)
					{
						fortifyTarget=countries[adjoiningCountries[i]];
						highestVulnerability=vulnerability;
					}
				}
			}
			if(fortifyTarget!=null)
			{
				board.fortifyArmies(us.getMoveableArmies(), us, fortifyTarget);
			}
		}
	}	// End of fortifyPhase() method

	private float calculateStrategicValue(Country us) {
		// TODO Auto-generated method stub
		return 0;
	}
	private float calculateImportance(Country country) 
	{
		
		int countryOwner=country.getOwner();
		int continentCode=country.getContinent();
		CountryIterator continent = new ContinentIterator(continentCode, countries);
		int countryCount=0;
		int ownedCount=0;
		while(continent.hasNext())
		{
			Country countryInContinent=continent.next();
			countryCount++;
			if(countryInContinent.getOwner()==countryOwner)
			{
				ownedCount++;
			}
		}
		float percentageOfContinent=1/countryCount;
		float percentageOwned=ownedCount/countryCount;
		float result= A * percentageOfContinent + B * percentageOwned;
		return result;
	}

	private float calculateVulnerability(Country country) 
	{
		// TODO Auto-generated method stub
		return 0;
	}
	
	private float howDivided(Country country) 
	{
		int[] hostileCountries=country.getHostileAdjoiningCodeList();
		List <Integer> IDList = new ArrayList <Integer>();
		for(int i=0; i<hostileCountries.length; i++)
		{
			int countryID=countries[hostileCountries[i]].getOwner();
			if(!IDList.contains(countryID))
			{
				IDList.add(countryID);
			}			
		}
		return IDList.size();
	
	}
	
	private float weightedTroopValue(Country country) 
	{
		// TODO Auto-generated method stub
		return 0;
	}
	
	private int[] getContinents(int playerID)
	{
		List <Integer> continentCodes= new ArrayList <Integer>();
		int[] result = new int[BoardHelper.numberOfContinents(countries)];
		CountryIterator playerCountries = new PlayerIterator(playerID, countries);
		int i=0;
		while(playerCountries.hasNext())
		{
			Country us = playerCountries.next();
			int continentCode=us.getContinent();
			if(!continentCodes.contains(continentCode))
			{
				continentCodes.add(continentCode);
				result[i]=continentCode;
				i++;
			}
		}
		return result;
	}
	
	private float calculateStability(int playerID) 
	{
		int continent;
		int[] continents = getContinents(playerID);
		float greatestVulnerability=-100000;
		float continentsHeld=0;
		for(int i=0; i<continents.length; i++)
		{
			continent=continents[i];
			//if player owns this continent
			if(BoardHelper.playerOwnsContinent(playerID, continent, countries))
			{
				continentsHeld++;
				//check boarders of this continent
				int[] boarderCountries=BoardHelper.getContinentBorders(continent, countries);
				for(int j=0; j<boarderCountries.length; j++)
				{
					//check all boarders within this country for most vulnerable overall
					float vulnerability=calculateVulnerability(countries[boarderCountries[j]]);
					if(vulnerability>greatestVulnerability)
					{
						greatestVulnerability=vulnerability;
					}
				}
			}
		}
		float result=(E*continentsHeld)/(F*greatestVulnerability);
		return result;
	}
	
	
	private float calculateThreat(int playerID) 
	{
		// TODO Auto-generated method stub
		return 0;
	}
	
	private float calculateAdvantage(int playerID) 
	{
		float stability=calculateStability(playerID);
		float threat=calculateThreat(playerID);
		float result=K*stability-L*threat;
		return result;
	}
	
	private float calculateRecklessness(Country country) 
	{
		// TODO Auto-generated method stub
		return 0;
	}

	private boolean plausibleAttack(Country attacker, Country target) {
		// TODO Auto-generated method stub
		return false;
	}

	private boolean evaluateAttackPhase(int countriesConquered) {
		//return TRUE if attacking should continue
		
		return false;
	}

	// Oh boy. If this method ever gets called it is because we have won the game.
	// Send back something witty to tell the user.
	public String youWon()
		{ 
		String answer = "The machines are learning";

		return answer;
		}

	/** We get notified through this method when certain things happen. */
	public String message( String message, Object data )
		{
		if ("youLose".equals(message))
			{
			int conqueringPlayerID = ((Integer)data).intValue();
			// now you could log that you have lost this game...
//			board.playAudioAtURL("http://sillysoft.net/sounds/boo.wav");
			}
		else if ("attackNotice".equals(message))
			{
			List dataList = (List) data;
			int attackingCountryCode = ((Integer)dataList.get(0)).intValue();
			int defendingCountryCode = ((Integer)dataList.get(1)).intValue();
			// now you could log that an attack has been performed...
//			System.out.println("Attack from country "+attackingCountryCode+" (owned by "+countries[attackingCountryCode].getOwner()+", "+board.getAgentName(countries[attackingCountryCode].getOwner())+") to "+defendingCountryCode+" (owned by "+countries[defendingCountryCode].getOwner()+", "+board.getAgentName(countries[defendingCountryCode].getOwner())+")");
			}
		else if ("chat".equals(message))
			{
			List dataList = (List) data;
			String from = (String) dataList.get(0);
			String chatText = (String) dataList.get(1);
			// now you could log the chat data
//			System.out.println("Player '"+from+"' said: "+chatText);
			}
		else if ("emote".equals(message))
			{
			List dataList = (List) data;
			String from = (String) dataList.get(0);
			String emoteText = (String) dataList.get(1);
			// now you could log the emote data
//			System.out.println("Player '"+from+"' emoted: "+emoteText);
			}
		return null;
		}
	      
	}	// End of Learning Agent Class