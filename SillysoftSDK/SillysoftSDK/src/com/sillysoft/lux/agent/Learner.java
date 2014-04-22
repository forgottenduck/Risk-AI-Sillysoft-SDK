package com.sillysoft.lux.agent;
//package java.lang;

import com.sillysoft.lux.*;
import com.sillysoft.lux.util.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;

/**
 * This class is an adaptive AI that was designed for CSE 5523 (Machine Learning) at The Ohio State University.
 * It makes use of dynamic scripting and weighted game policy techniques to refine its strategy.
 * 
 * @authors Christopher Meek, Kyle Donovan
 */
public class Learner extends SmartAgentBase {
	// values used in tactics analysis, adjusted via learning weights
		float recklessness;
		float minRecklessness, maxRecklessness;
		float recklessFortifyThreshold;
		float recklessCardThreshold;
	// fine-tuning weights that can be adjusted via the rule set
		Rule[] deployRules = new Rule[280];
		Rule[] attackRules = new Rule[280];
		Rule[] fortifyRules = new Rule[280];
		float[] deployWeights = new float[14];
		float[] attackWeights = new float[14];
		float[] fortifyWeights = new float[14];
		// A filename for the log
		private String fileName;
		private String rulesPath = Board.getAgentPath() + "rules.txt";
		private String reckPath = Board.getAgentPath() + "reck.txt";
		private float explorationThreshold = 0.15f; // probability to explore instead of exploit (0.0 - 1.0 range)
		private String[] lettersArray = {"A","B","C","D","E","F","G","H","I","J","K","L","M","N"};
		private Boolean didSetup = false;
		private int turnCount;
		
	public float version() {
		return 1.0f;
	}

	public String description() {
		String result = "Learner is an AI that uses dynamic scripting and weighted game policy to improve its Risk strategy.";
		return result;
	}

	public String name() {
		String result = "Learner";
		return result;
	}

	@Override
	public void placeInitialArmies( int numberOfArmies )
	{
		if (didSetup == false) {
			setup();
			didSetup = true;
		}
		placeArmies(numberOfArmies);
	}
	
	public void placeArmies( int numberOfArmies )
	{
		makeLogEntry("------DEPLOY PHASE------\n");

		Country mostValuableCountry = null;
		float largestStrategicValue=-100000;
		// Use a PlayerIterator to cycle through all the countries that we own.
		CountryIterator own = new PlayerIterator( ID, countries );
		while(numberOfArmies>0)
		{
			while (own.hasNext()) 
			{
				Country us = own.next();
				float strategicValue=calculateStrategicValue(us, fortifyWeights);
				
				// If it's the best so far store it
				//makeLogEntry("strategic value: " + strategicValue + ".\n");
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
	
	public void cardsPhase( Card[] cards )
	{
		turnCount++;
		Card[] set=null;
		if(cards.length==5 || recklessness>calculateRecklessCardThreshold(deployWeights))
		{
			set=Card.getBestSet(cards, ID, countries);
		}
		if(set!=null)
		{
			board.cashCards(set[0], set[1], set[2]);
		}
	}
	
	
public void attackPhase()
{
	makeLogEntry("------ATTACK PHASE------\n");
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
			float strategicValue=calculateStrategicValue(countries[possibleTargets[i]], attackWeights);
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
	
	makeLogEntry("------FORTIFY PHASE------\n");

	// Cycle through all the countries and find countries that we could move from:
	// if country has no surrounding enemies, move armies toward country with most strategic value
	// otherwise check recklessness to decide how to move armies
	CountryIterator armies = new ArmiesIterator( ID, 2, countries );
	
	while(armies.hasNext())
	{
		Country us=armies.next();
		int[] adjoiningCountries = us.getFriendlyAdjoiningCodeList();
		Country fortifyTarget=null;
		//If surrounded by friendly territories
		//move in random direction
		if(us.getAdjoiningList().length==adjoiningCountries.length)
		{
			Random rand=new Random();
			int j=rand.nextInt(adjoiningCountries.length);
			fortifyTarget=countries[adjoiningCountries[j]];
		}
		// if reckless, move to attack position
		else if(recklessness>calculateRecklessFortifyThreshold(fortifyWeights))
		{
			float highestStrategicValue=calculateStrategicValue(us, fortifyWeights);
			for(int i=0; i<adjoiningCountries.length;i++)
			{
				float strategicValue=calculateStrategicValue(countries[adjoiningCountries[i]], fortifyWeights);
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
			float highestVulnerability=calculateVulnerability(us, fortifyWeights);
			for(int i=0; i<adjoiningCountries.length;i++)
			{
				float vulnerability=calculateVulnerability(countries[adjoiningCountries[i]], fortifyWeights);
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
	makeLogEntry("Turn "+board.getTurnCount()+" complete\n\n");
}



	// methods for machine learning aspects of the AI
	

	private float calculateRecklessCardThreshold(float[] weights)
	{
		float result=weights[12]*(maxRecklessness-minRecklessness);
		return result;
	}
	
	private float calculateRecklessAttackThreshold(float[] weights)
	{
		float result=weights[13]*(maxRecklessness-minRecklessness);
		return result;
	}
	
	private float calculateRecklessFortifyThreshold(float[] weights)
	{
		float result=weights[11]*(maxRecklessness-minRecklessness);
		return result;
	}
	
	
	private float howDivided(Country country, float[] weights) 
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
		float result=IDList.size()+1*weights[2];
		//makeLogEntry("How Divided calculated as: " + result + "\n");
		return result;
	}
	
	/**
	 * This method weights the number of troops according to their distance, with closer troops being more relevant.
	 * 
	 * @param numberOfTroops
	 * @return The weighted number of troops
	 */
	public float calculateWeightedTroopValue(Country srcCountry, Country destCountry, float weights[]) {
		float result = 0;
		Country[] path = BoardHelper.easyCostBetweenCountries(srcCountry,destCountry, countries);
		if (path != null) {
			int distance = path.length;
			result = weights[3] * destCountry.getArmies()/(float) distance;
		} else {
			result = 0;
		}
		//makeLogEntry("Weighted troop value for "+srcCountry.getName()+" to "+destCountry.getName()+" calculated as: "+result);
		return result;
	}
	/**
	 * This method calculates the strategic value of a country.
	 * A high strategic value means the country is valuable to its owner and is unattractive to potential attackers.
	 * A low strategic value means the country is not valuable to its owner and is attractive to potential attackers.
	 * 
	 * @param country The country for which the strategic value is to be calculated
	 * @return The strategic value of the country as a float between 0.0 and 1.0
	 */
	public float calculateStrategicValue(Country country, float[] weights) {
		float result = 0;
		float advantage = calculateAdvantage(ID, weights);
		makeLogEntry("Advantage for strat value calculated as: " + advantage + "\n");
		result = (calculateRecklessness(advantage)*calculateImportance(country, weights))/(calculateVulnerability(country, weights)/calculateRecklessness(advantage));
		//makeLogEntry("Strategic value calculated as: " + result + "\n");
		return result;
	}
	
	/**
	 * This method calculates the vulnerability of a country.
	 * 
	 * @param country The country for which the vulnerability is to be calculated
	 * @return The vulnerability of the country as a float between 0.0 and 1.0
	 */
	public float calculateVulnerability(Country country, float[] weights) {
		float result = 0;
		int enemyTroops = 0;
		int friendlyTroops = 0;
		for (int i=0; i<numContinents; i++) {
			ContinentIterator itr = new ContinentIterator(i, countries);
			while (itr.hasNext()) {
				Country otherCountry = itr.next();
				if (otherCountry.getOwner() != ID) { // if the country is owned by an enemy
					enemyTroops += calculateWeightedTroopValue(country, otherCountry, weights);
				}
				else
				{
					friendlyTroops += calculateWeightedTroopValue(country, otherCountry,weights);
				}
			}
		}
		
		float divided = howDivided(country,weights);
		result = (enemyTroops/divided) - friendlyTroops + 0.00001f;
		//makeLogEntry("Vulnerability calculated as: " + result + "\n");
		return result;
	}
	
	/**
	 * This method calculates the threat of an enemy player.
	 * 
	 * @param player The player whose threat is to be calculated
	 * @return The threat of the player as a float between 0.0 and 1.0
	 */
	public float calculateThreat(int player, float[] weights) {
		float result = 0;
		// threat = (G*troop_income + H*card_count)/J*distance
		// "distance" is a little abstract, since we're talking about the enemy as a whole
		int income = board.getPlayerIncome(player);
		int cards = board.getPlayerCards(player);
		result = weights[7]*income + weights[8]*cards;
		//makeLogEntry("Threat calculated as: " + result + "\n");
		return result;
	}
	
	/**
	 * This method calculates the current recklessness value.
	 * @param advantage The advantage value returned by getAdvantage()
	 * @return The current recklessness value
	 */
	public float calculateRecklessness(float advantage) {
		float result = 0;
		// Recklessness = advantage + number of turns taken + current card bonus
		int turnsTaken = board.getTurnCount(); // check that these two methods do what we actually need
		int bonus = board.getNextCardSetValue();
		result = advantage + turnsTaken + bonus;
		// update min and max recklessness if we've expanded the range
		if (result < minRecklessness) {
			minRecklessness = result;
		}
		if (result > maxRecklessness) {
			maxRecklessness = result;
		}
		makeLogEntry("Recklessness calculated as: " + result + "\n");
		return result;
	}
	private float calculateImportance(Country country, float[] weights) 
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
		float result= weights[0] * percentageOfContinent + weights[1] * percentageOwned;
		//makeLogEntry("Importance calculated as: " + result + "\n");

		return result;
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
	private float calculateStability(int playerID, float[] weights) 
	{
		int continent;
		int[] continents = getContinents(playerID);
		float greatestVulnerability=0;
		float continentsHeld=0;
		for(int i=0; i<continents.length; i++)
		{
			continent=continents[i];
			//if player owns this continent
			if(BoardHelper.playerOwnsContinent(playerID, continent, countries))
			{
				continentsHeld++;
				//check boarders of this continent
				int[] borderCountries=BoardHelper.getContinentBorders(continent, countries);
				greatestVulnerability=calculateVulnerability(countries[borderCountries[0]], weights);
				for(int j=0; j<borderCountries.length; j++)
				{
					//check all boarders within this country for most vulnerable overall
					float vulnerability=calculateVulnerability(countries[borderCountries[j]], weights);
					if(vulnerability>greatestVulnerability)
					{
						greatestVulnerability=vulnerability;
					}
				}
				
			}
		}
		int armiesCount=BoardHelper.getPlayerArmies(playerID, countries);
		//makeLogEntry("Greatest Vulnerability for stability calculation: " + greatestVulnerability + "\n");
		float result;
		if(continentsHeld>0)
		{
			result=(weights[4]*board.getPlayerIncome(playerID) + weights[5]*armiesCount)/(weights[6]*greatestVulnerability);
		}
		else
		{
			result=10;
		}
		//makeLogEntry("Stability calculated as: " + result + "\n");
		//makeLogEntry("stability calculated as: " + result + ".\n");
		return result;
	}
	
	private float calculateAdvantage(int playerID, float[] weights) 
	{
		float stability=calculateStability(playerID, weights);
		int[] enemyPlayers = getEnemyPlayerIDs(playerID);
		float maxThreat=1;
		for(int i=0; i<enemyPlayers.length; i++)
		{
			float threat=calculateThreat(enemyPlayers[i], weights);
			if(threat>maxThreat)
			{
				maxThreat=threat;
			}
		}
		makeLogEntry("Advantage calculated as: "+weights[9]+"*"+stability+"-" +weights[10]+"*" +maxThreat+ "\n");
		float result=weights[9]*stability-weights[10]*maxThreat;
		//makeLogEntry("Advantage calculated as: " + result + "\n");
		//makeLogEntry("advantage calculated as: " + result + ".\n");
		return result;
	}
	private int[] getEnemyPlayerIDs(int playerID)
	{
		
		int remainingPlayers=board.getNumberOfPlayersLeft()-1;
		int[] result=new int[remainingPlayers];
		List <Integer> enemyIDs= new ArrayList <Integer>();
		int i=0;
		int j=0;
		while(i<remainingPlayers)
		{
			int currentID=countries[j].getOwner();
			Integer ID=new Integer(currentID);
			if(currentID!=playerID && ! enemyIDs.contains(ID))
			{
				result[i]=currentID;
				enemyIDs.add(ID);
				i++;
			}
			j++;
		}
		
		return result;
	}
	
	
	private boolean plausibleAttack(Country attacker, Country target) {
		if(recklessness>calculateRecklessAttackThreshold(attackWeights)&&attacker.getArmies()>5)
		{
			return true;
		}
		else
		{
			return attacker.getArmies()>target.getArmies();
		}
	}

	private boolean evaluateAttackPhase(int countriesConquered) {
		//return TRUE if attacking should continue
		if(recklessness>calculateRecklessAttackThreshold(attackWeights))
		{
			return true;
		}
		else if(countriesConquered>1)
		{
			return false;
		}
		else
		{
			return true;
		}
	}
	@Override
	public int pickCountry()
	{
	int goalCont = BoardHelper.getSmallestPositiveEmptyCont(countries, board);

	if (goalCont == -1) // oops, there are no unowned conts
		goalCont = BoardHelper.getSmallestPositiveOpenCont(countries, board);

	// So now pick a country in the desired continent
	return pickCountryInContinent(goalCont);
	}
	
	
	public String youWon()
	{ 
		// run the "win" fitness function to adjust rules
		// store the new weight values
		String answer = "The machines are learning";
		float gameResult = winFitnessFunction();
		adjustRules(gameResult);
		return answer;
	}

	public String message( String message, Object data )
	{
	if ("youLose".equals(message))
		{
			float gameResult = lossFitnessFunction();
			adjustRules(gameResult);
		}
	return null;
	}
	
	// return a negative number which will be added to a rank to "increase" it
	public float winFitnessFunction() {
		float result = -1.0f;
		return result;
	}
	
	// return a positive number which will be added to a rank to "decrease" it
	public float lossFitnessFunction() {
		int players = board.getNumberOfPlayersLeft();
		float result;
		int turnsFromDefeat=board.getTurnCount()-turnCount;
		if(turnsFromDefeat>0.8*board.getTurnCount())
		{
			result=5;
		} else if (turnsFromDefeat > 0.6*board.getTurnCount()){
			result=4;
		}else if (turnsFromDefeat > 0.4*board.getTurnCount()){
			result=3;
		}else if (turnsFromDefeat > 0.2*board.getTurnCount()){
			result=2;
		}else{
			result=1;
		}
		return result;
	}
	
	public String[] getRawRulesInput() {
		File rulesFile = new File (rulesPath);
		FileReader reader;
		char[] raw = new char[(int)rulesFile.length()];
		try {
			reader = new FileReader(rulesFile);
			try {
				reader.read(raw);
				reader.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
		}
		String rawAsString = new String(raw);
		String[] result = rawAsString.split("[\r\n]+---[\r\n]+");
		return result;
	}
	
	public float[] getReckValues() {
		float result[] = new float[lettersArray.length];
		 //makeLogEntry("getWeightValues called\n");
		 File reckFile = new File(reckPath);
		 FileReader reader;
		 // get the min and max recklessness values
		 char[] reck = new char[(int)reckFile.length()];
		 try {
				reader = new FileReader(reckFile);
				try {
					reader.read(reck);
					reader.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
				}
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
			}
		 String rawAsString = new String(reck);
		 String[] minMaxRecklessness = rawAsString.split("_");
		 //makeLogEntry("Setting minReck to " + minMaxRecklessness[0] + ", maxReck to " + minMaxRecklessness[1] + ".\n");
		 result[0] = Float.valueOf(minMaxRecklessness[0]).floatValue();
		 result[1] = Float.valueOf(minMaxRecklessness[1]).floatValue();
		 return result;
	}
	public Rule[] getDeployRules(String input) {
		 //makeLogEntry("getWeightValues called\n");
		// get the array of deploy rules
		
		String deployString = new String(input);
		String[] deployRulesStrings = deployString.split("[\r\n]+");
		Rule[] result = new Rule[deployRulesStrings.length];
		for (int i=0; i < deployRulesStrings.length; i++) {
			result[i] = new Rule(deployRulesStrings[i]);
		}
		// sort in ascending rank (1,2,...,n) i.e., better rules first
		RuleComparator<Rule> c = new RuleComparator<Rule>();
		Arrays.sort(result, c);
		
		return result;
	}
	
	public float[] getDeployWeights(Rule[] rules) {
		float[] result = new float[lettersArray.length];
		// for A-L, find the first rule that mentions that letter
				Rule deployRule;
				for (int i = 0; i < lettersArray.length; i++) {
					int j = 0;
					while (true) {
						deployRule = new Rule(rules[j].toString());
						if (j < rules.length - 1) { // iterate if not at the end
							j++;
						} else { // otherwise start over
							j = 0;
						}
						if (deployRule.getName().equals(lettersArray[i]) == true || rand.nextFloat() > explorationThreshold) {
							// assign that rule's weight to the corresponding letter's index (A=0,B=1,...)
							result[i] = deployRule.getWeight();
						}
						else { // stop iterating with probability P = (1 - explorationThreshold) if a matching rule is found
							break;
						}
					} 	
				}
		return result;
	}
	
	public Rule[] getAttackRules(String input) {
		// makeLogEntry("getWeightValues called\n");
		// get the array of attack rules
		File rulesFile = new File (rulesPath);
		FileReader reader;
		char[] raw = new char[(int)rulesFile.length()];
		try {
			reader = new FileReader(rulesFile);
			try {
				reader.read(raw);
				reader.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
		}
		String rawAsString = new String(raw);
		String[] threeArrays = rawAsString.split("[\r\n]+---[\r\n]+");
		String attackString = new String(input);
		String[] attackRulesStrings = attackString.split("[\r\n]+");
		Rule[] result = new Rule[attackRulesStrings.length];
		for (int i=0; i < attackRulesStrings.length; i++) {
			result[i] = new Rule(attackRulesStrings[i]);
		}
		// sort in ascending rank (1,2,...,n) i.e., better rules first
		RuleComparator<Rule> c = new RuleComparator<Rule>();
		Arrays.sort(result, c);
		
		return result;
	}
	
	public float[] getAttackWeights(Rule[] rules) {
		float[] result = new float[lettersArray.length];
		// for A-L, find the first rule that mentions that letter
				Rule attackRule;
				for (int i = 0; i < lettersArray.length; i++) {
					int j = 0;
					while (true) {
						attackRule = new Rule(rules[j].toString());
						if (j < rules.length - 1) { // iterate if not at the end
							j++;
						} else { // otherwise start over
							j = 0;
						}
						if (attackRule.getName().equals(lettersArray[i]) == true || rand.nextFloat() > explorationThreshold) {
							// assign that rule's weight to the corresponding letter's index (A=0,B=1,...)
							result[i] = attackRule.getWeight();
						}
						else { // stop iterating with probability P = (1 - explorationThreshold) if a matching rule is found
							break;
						}
					} 	
				}
		return result;
	}
	
	public Rule[] getFortifyRules(String input) {
		 //makeLogEntry("getWeightValues called\n");
		// get the array of fortify rules
		File rulesFile = new File (rulesPath);
		FileReader reader;
		char[] raw = new char[(int)rulesFile.length()];
		try {
			reader = new FileReader(rulesFile);
			try {
				reader.read(raw);
				reader.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
		}
		String rawAsString = new String(raw);
		String[] threeArrays = rawAsString.split("[\r\n]+---[\r\n]+");
		String fortifyString = new String(input);
		String[] fortifyRulesStrings = fortifyString.split("[\r\n]+");
		Rule[] result = new Rule[fortifyRulesStrings.length];
		for (int i=0; i < fortifyRulesStrings.length; i++) {
			result[i] = new Rule(fortifyRulesStrings[i]);
		}
		// sort in ascending rank (1,2,...,n) i.e., better rules first
		RuleComparator<Rule> c = new RuleComparator<Rule>();
		Arrays.sort(result, c);
		
		return result;
	}
	
	public float[] getFortifyWeights(Rule[] rules) {
		float[] result = new float[lettersArray.length];
		// for A-L, find the first rule that mentions that letter
				Rule fortifyRule;
				for (int i = 0; i < lettersArray.length; i++) {
					int j = 0;
					while (true) {
						fortifyRule = new Rule(rules[j].toString());
						if (j < rules.length - 1) { // iterate if not at the end
							j++;
						} else { // otherwise start over
							j = 0;
						}
						if (fortifyRule.getName().equals(lettersArray[i]) == true || rand.nextFloat() > explorationThreshold) {
							// assign that rule's weight to the corresponding letter's index (A=0,B=1,...)
							result[i] = fortifyRule.getWeight();
						}
						else { // stop iterating with probability P = (1 - explorationThreshold) if a matching rule is found
							break;
						}
					} 	
				}
		return result;
	}
		
	
	public void adjustRules(float adjustment) {
		// update min and max recklessness in case they've changed this round
		String newReck = "";
		String newMin = String.valueOf(minRecklessness);
		String newMax = String.valueOf(maxRecklessness);
		newReck = newReck + newMin + "_" + newMax;
		File reckFile = new File(reckPath);
		FileWriter writer;
		try {
			writer = new FileWriter(reckFile, false);
			writer.write(newReck);
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String newDeployRules = "";
		String newAttackRules = "";
		String newFortifyRules = "";

		// change each fortify weight's rank by amount adjustment - determined by the fitness function and passed in
		for (int i=0; i < fortifyWeights.length; i++) {
			String name = lettersArray[i];
			Float weight = new Float(fortifyWeights[i]);
			//makeLogEntry("fortifyRules: " + Arrays.toString(fortifyRules));
			for (int j = 0; j<fortifyRules.length; j++) {
				Rule rule = new Rule(fortifyRules[j].toString());
				if (rule.getName().equals(name) && Math.abs(rule.getWeight() - weight.floatValue()) < 0.001) {
					int currentRank = rule.getRank();
					currentRank = currentRank + (int) adjustment;
					rule.SetRank(currentRank);
				}
			}
		}
		// change each attack weight's rank by amount gameResult - determined by the fitness function and passed in
		for (int i=0; i < attackWeights.length; i++) {
			String name = lettersArray[i];
			Float weight = new Float(attackWeights[i]);
			for (Rule rule : attackRules) {
				if (rule.getName().equals(name) && Math.abs(rule.getWeight() - weight.floatValue()) < 0.001) {
					int currentRank = rule.getRank();
					currentRank = currentRank + (int)adjustment;
					rule.SetRank(currentRank);
				}
			}
		}
		// change each fortify weight's rank by amount gameResult - determined by the fitness function and passed in
		for (int i=0; i < fortifyWeights.length; i++) {
			String name = lettersArray[i];
			Float weight = new Float(fortifyWeights[i]);
			for (Rule rule : fortifyRules) {
				if (rule.getName().equals(name) && Math.abs(rule.getWeight() - weight.floatValue()) < 0.001) {
					int currentRank = rule.getRank();
					currentRank = currentRank + (int)adjustment;
					rule.SetRank(currentRank);
				}
			}
		}
		// convert the rules to string format
		
		for (int i=0; i < deployRules.length; i++) {
			newDeployRules = newDeployRules + deployRules[i].toString() + "\n";
		}
		newDeployRules = newDeployRules + "---\n";
		for (int i=0; i < attackRules.length; i++) {
			newAttackRules = newAttackRules + attackRules[i].toString() + "\n";
		}
		newAttackRules = newAttackRules + "---";
		for (int i=0; i < fortifyRules.length; i++) {
			newFortifyRules = newFortifyRules + "\n" + fortifyRules[i].toString();
		}
				
		// write the changes to disk for persistence, overwriting the old values
		File rulesFile = new File(rulesPath);
		
		try {
			writer = new FileWriter(rulesFile, false);
			writer.write(newDeployRules + newAttackRules + newFortifyRules);
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	
	}
	
	public void setup() {
		rand = new Random();
		float[] minMax = getReckValues();
		minRecklessness = minMax[0];
		maxRecklessness = minMax[1];
		String[] threeArrays = getRawRulesInput();
		deployRules = getDeployRules(threeArrays[0]);
		deployWeights = getDeployWeights(deployRules);
		attackRules = getAttackRules(threeArrays[1]);
		attackWeights = getAttackWeights(attackRules);
		fortifyRules = getFortifyRules(threeArrays[2]);
		fortifyWeights = getFortifyWeights(fortifyRules);
	}
	
	public void makeLogEntry(String message) {
		FileWriter writer;
		try {
			// write to the file
			if (fileName == null) {
				Date date = new Date();
				fileName = date.toString();
				fileName = fileName.replace(' ', '-');
				fileName = fileName.replace(":", "");
				File file = new File(Board.getAgentPath() + File.separator + name() + "Logs" + File.separator + fileName + ".txt");
				file.getParentFile().mkdirs();
				writer = new FileWriter(file, false);
			} else {
				writer = new FileWriter(Board.getAgentPath() + File.separator + name() + "Logs" + File.separator + fileName + ".txt", true);
			}
			writer.write(message);
			writer.close(); // flush and close
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	} 
	class Rule {
		
		private String name;
		private Float weight;
		private Integer rank;
		
		public Rule(String input) {
			String[] values = input.split("_");
			name = values[0];
			weight = Float.parseFloat(values[1]);
			rank = Integer.parseInt(values[2]);
		}
		
		public String getName() {
			String result = name;
			return result;
		}
		public int setName(String input) {
			name = input;
			return 0;
		}
		public float getWeight() {
			float result = weight.floatValue();
			return result;
		}
		public int setWeight(float input) {
			weight = input;
			return 0;
		}
		public int getRank() {
			int result = rank.intValue();
			return result;
		}
		public int SetRank(int input) {
			rank = input;
			return 0;
		}
		public String toString() {
			String result = name.toString() + "_" + weight.toString() + "_" + rank.toString();
			return result;
		}
	}
	
	class RuleComparator<T> implements Comparator<T> {

		public int compare(T o1, T o2) {
			int result;
			Rule rule1 = (Rule)o1;
			Rule rule2 = (Rule)o2;
			if (rule1.getRank() < rule2.getRank()) {
				result = -1;
			} else if (rule1.getRank() > rule2.getRank()) {
				result = 1 ;
			} else {
				result = 0;
			}
			return result;
		}

	}
}
