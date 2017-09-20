import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import scala.collection.mutable.ArrayBuffer
import java.nio.file.{Paths, Files}

object TScorify {
    var verbose = false;    // Flag for print updates
    var inputDirectory = "./";  // Directory name to read input files from
    var outputDirectory = "./TScorify_Result/" // Directory name to write results into
    var choseInputDir = false;
    var choseOutputDir = false;
    val optionPattern1 = "\\[{0,1}--(\\p{Print}+)\\]{0,1}=(\\p{Print}+)".r
    val optionPattern2 = "\\[{0,1}--(\\p{Print}+)\\]{0,1}".r
    val fileNamePattern = "(.*)/(.*.txt)".r

    // Helper method to exit this app
    def exitApp(sc: Option[SparkContext], code: Int): Unit = {
        println()
        sc match {
            case Some(s) => s.stop
            case None => 
        }
        System.exit(code)
    }

    // Calculates cumulative frequency of words per file
    def convertFreq(obj: (String, String)): (String, Map[String, Int]) = {
        (obj._1, obj._2.split("[[^'_-]&&\\p{Punct}\\s]+")
        .filter(word => word.length > 2 && word.matches("[A-Za-z0-9]+"))
        .map(word => word.toLowerCase)
        .groupBy(word => word)
        .mapValues(_.size)
        .map(identity))
    }

    // Helper method to abbreviate strings
    def abbrevName(name: String, length: Int, midEllipsis: Boolean): String = {
        if(midEllipsis) {
            if(name.length > length) {
                name.substring(0, (length/2 - 1)).concat("...").concat(name.substring(name.length - (length/2 - 2), name.length))
            }
            else {
                name
            }
        }
        else {
            if(name.length > length) { 
                name.substring(0, (length - 3)).concat("...")
            }
            else {
                name
            }
        }
    }

    // Shortens filename string
    def shortenFileName(fileName: String, length: Int): String = {
        fileName match {
            case fileNamePattern(dir, file) => abbrevName(file, length, true)
            case _ => abbrevName(fileName, length, true)
        }
    }

    // Removes directory path name substring from filename
    /*def removeDirName(fileName: String): String = {
        fileName match {
            case fileNamePattern(dir, file) => file
            case _ => fileName
        }
    }*/

    // Merges an array of maps into one, sums up values of equal keys
    def mergeMaps(maps: ArrayBuffer[Map[String, Int]]): Map[String, Int] = {
        maps.reduceLeft((tail, head) => head.foldLeft(tail) {
            case(finalMap, (keyword, count)) => finalMap + (keyword ->(count + finalMap.getOrElse(keyword, 0)))
        })
    }

    // Determines how many files contain the particular word
    def aggregateWordContained(word: String, arr: Array[(String, Map[String, Int])]): Int = {
        var count = 0
        arr.foreach(obj =>
            if(obj._2.contains(word)) {
                count += 1
            }
        )
        count
    }

    // Simple Keyword Scoring Algorithm
    /*def calcWordScore(wordFreq: Int, totalFreq: Int, wordCount: Int, totalCount: Int): Double = {
        ((wordFreq.toDouble/wordCount)*(wordFreq.toDouble/wordCount)*totalCount).toDouble/((totalFreq.toDouble/totalCount)*wordCount)
    }*/

    // TF_IDF Keyword Scoring Algorithm
    def calcWordScoreTFIDF(wordLength: Int, wordFreq: Int, maxFreq: Int, totalDocs: Long, wordContained: Int): Double = {
        val tf = 0.5 + 0.5*(wordFreq.toDouble/maxFreq)
        val idf = math.log(totalDocs.toDouble/(wordContained + 1))
        if(wordLength == 3) {
            tf*idf*0.99
        }
        else if(wordLength == 4) {
            tf*idf*0.995
        } else {
            tf*idf    
        }
    }

    // TextAnalyzer Keyword Scoring Algorithm
    /*def calcWordScoreX(wordFreq: Int, totalFreq: Int, wordCount: Int, totalCount: Int): Double = {
         Math.tanh((wordFreq.toDouble/wordCount)/(wordCount*200)) - 
         5*Math.tanh(((totalFreq.toDouble/totalCount) - (wordFreq.toDouble/wordCount)).toDouble/(totalCount - wordCount)*200)
    }*/

    def main(args: Array[String]) {
        println("\nTScorify initiated. For help, use [--help] as a command-line argument.")
        println("-----------------------------------------------------------------------")
        val t0 = System.nanoTime

        // Consume command-line arguments (user input). Decide on input and output directories
        args.foreach( s =>
            s match {
                case optionPattern1(option, dir_name) => {
                    val opt = option.toLowerCase
                    opt match {
                        case "input" | "i" => {
                            if(choseInputDir) {
                                println("[Warning]: Discarded directory file input option, [%s].\n".format(dir_name)
                                .concat("           Input directory is already set to [%s].".format(inputDirectory)))
                            }
                            else {
                                inputDirectory = dir_name
                                if(inputDirectory.charAt(inputDirectory.length - 1) != '/') inputDirectory = inputDirectory.concat("/")
                                println("[Input Directory]: Set to [%s].".format(inputDirectory))
                                choseInputDir = true
                            }
                        }
                        case "output" | "o" => {
                            if(choseOutputDir) {
                                println("[Warning]: Discarded directory file output option, [%s].\n".format(dir_name)
                                .concat("           Output directory is already set to [%s].".format(outputDirectory)))
                            }
                            else {
                                outputDirectory = dir_name
                                if(inputDirectory.charAt(outputDirectory.length - 1) != '/') outputDirectory = outputDirectory.concat("/")
                                println("[Output Directory]: Set to [%s].".format(outputDirectory))
                                choseOutputDir = true
                            }
                        }
                        case _ => println("[Warning]: Unexpected option [%s] used. Ignored.".format(s))
                    }
                }
                case optionPattern2(option) => {
                    val opt = option.toLowerCase
                    opt match {
                        case "verbose" | "v" => { verbose = true; println("[Verbose]: Enabled.") }
                        case "help" | "h" =>   println("\n[Help]: TScorify (Optional) Options:\n"
                                                .concat("   [--verbose] => Enable console print updates.\n")
                                                .concat("   [--help] => Display help.\n")
                                                .concat("   [--input=input_dir_name] => Type directory name containing target files.\n")
                                                .concat("        By default, the directory is set to [./].\n")
                                                .concat("   [--output=output_dir_name] => Type unused directory name to save results.\n")
                                                .concat("        By default, the directory is set to [./TScorify_Result/].\n"))
                        case _ => println("[Warning]: Unexpected option [%s] used. Ignored.".format(s))
                    }
                }
                case _ => println("[Warning]: Unexpected option [%s] used. Ignored.".format(s))
            }
        )
        if(!choseInputDir) {
            println("[Input Directory]: Set to [%s] by default.".format(inputDirectory))
        }
        if(!choseOutputDir) {
            println("[Output Directory]: Set to [%s] by default.".format(outputDirectory))
        }
        
        // Check if given file paths are valid
        if(!Files.exists(Paths.get(inputDirectory))) {
            println("[Error]: Input directory [%s] does not exist. Exiting.".format(inputDirectory))
            exitApp(None, -1)
        }
        if(Files.exists(Paths.get(outputDirectory))) {
            println("[Error]: Output directory [%s] already exists. Exiting.".format(outputDirectory))
            exitApp(None, -1)
        }

        // Set up Spark Context
        val conf = new SparkConf().setAppName("TScorify")
        val sc = new SparkContext(conf)
        sc.setLogLevel("ERROR")

        // Read input files
        if(verbose) println("Opening directory [%s]...\n".format(inputDirectory))
        val texts = sc.wholeTextFiles(inputDirectory.concat("*.txt")).cache
        var fileCount = 0L
        try {
            fileCount = texts.count
            if(verbose) {
                println("Scoring %s text files...\n".format(fileCount))
            }
        }
        catch {
            case _: Throwable => println("[Error]: Input directory [%s] contains zero text files. Exiting.".format(inputDirectory))
            exitApp(Some(sc), -1)
        }


        // Initiate lexing and build necessary data structures for upcoming keyword scoring
        if(verbose) println("Tokenizing and aggregating nontrivial word cumulative frequencies...\n")

        // Word cumulative frequencies of a particular file
        val fileFrequencies = texts.map(convertFreq).cache
        val fileFrequenciesCollected = fileFrequencies.collect

        // Word cumulative frequencies of all indexed files
        var mapArr = ArrayBuffer[Map[String,Int]]()
        fileFrequenciesCollected.foreach(obj => mapArr += obj._2)
        val totalFrequencies = mergeMaps(mapArr)

        // Word count of a particular file
        //val fileWordCount = Map(fileFrequencies.map(obj => (obj._1, obj._2.map(pair => pair._2).reduce(_ + _))).collect:_*)

        // Word count of all indexed files
        //val totalWordCount = totalFrequencies.map(pair => pair._2).reduce(_ + _)

        // Maximum cumulative frequency (any word) of a particlar file
        val maxFrequencies = fileFrequenciesCollected.map(obj => (obj._1, obj._2.reduceLeft{(o1, o2) => if(o1._2 > o2._2) o1 else o2})).toMap

        // File count containing a particular word
        val wordContainedCount = totalFrequencies.map(obj => obj._1 -> aggregateWordContained(obj._1, fileFrequenciesCollected))

        // Print filenames and their relative word counts
        if(verbose) {
            println("-----------------------------------------------------")
            println("| FILENAME                             | WORD COUNT |")
            println("-----------------------------------------------------")
            fileFrequencies.foreach(obj => println("| %-36s | %-10s |"
                           .format(shortenFileName(obj._1, 36), obj._2.size)))
            println("-----------------------------------------------------\n")
        }

        // Initiate keyword scoring. Firstly, build mappings between file and its (key)word scores
        if(verbose) println("Keyword reduction, scoring words based on relative frequency...\n")
        val fileScores = fileFrequencies.map(obj => (obj._1, obj._2.map(pair => 
            (pair._1, calcWordScoreTFIDF(pair._1.length, pair._2, maxFrequencies(obj._1)._2, fileCount, wordContainedCount(pair._1))))))
            //(pair._1, calcWordScore(pair._2, totalFrequencies(pair._1), fileWordCount(obj._1), totalWordCount)))))

        // Secondly, word vocabulary reduction. Select top % of (key)words based on highest scores for each file.
        val fileKeywordsList = fileScores.map(obj => (shortenFileName(obj._1, 45), obj._2.toList.sortBy(-_._2).take((15.67656*math.pow(obj._2.size, 0.1600591)).toInt + 1))).cache
        //val fileKeywordsMap = fileKeywordsList.map(obj => (obj._1, obj._2.toMap)).cache

        // Print filenames, their relative keyword counts, and top keyword (word with highest score)
        if(verbose) {
            println("------------------------------------------------------------------------------")
            println("| FILENAME                             | KEYWORD COUNT | TOP KEYWORD         |")
            println("------------------------------------------------------------------------------")
            fileKeywordsList.foreach(obj => println("| %-36s | %-13s | %-19s |"
                            .format(shortenFileName(obj._1, 36), obj._2.size, abbrevName(obj._2(0)._1, 19, false))))
            println("------------------------------------------------------------------------------\n")
        }

        // Save keywords list to output directory
        println("[Output Directory]: Writing results to [%s]".format(outputDirectory))
        fileKeywordsList.saveAsTextFile(outputDirectory)

        println("TScorify finished successfully in %d ms. Exiting.".format(((System.nanoTime - t0)/1e6).toInt))
        exitApp(Some(sc), 0)
    }
}