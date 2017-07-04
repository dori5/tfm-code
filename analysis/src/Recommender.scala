/**
  * Created by medu5a on 30/05/17.
  */

import org.apache.log4j.Logger
import org.apache.log4j.Level
import java.io._

import scala.io.Source
import java.util.Calendar
import scala.collection.mutable.{HashMap, HashSet}
import scala.util.Random
import org.apache.spark.ml.recommendation.{ALS, ALSModel}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

object Recommender {

  def main(args: Array[String]) {

    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)

    val spark = SparkSession.builder().getOrCreate()

    val base = "/data/"

    val original_dataset = base + "original_dataset.txt"
    val useful_dataset = base + "useful_dataset.txt"
    val window_dataset = base + "window_dataset.txt"
    val final_dataset = base + "final_dataset.txt"
    val auxiliary_dataset = base + "auxiliary_dataset.json"
    val clean_json = base + "clean_json.json"
    val clients = base + "clients.txt"
    val items = base + "items.txt"
    val auxiliary_item_data_full1 = base + "auxiliary_item_data_full1.txt"
    val auxiliary_item_data_full2 = base + "auxiliary_item_data_full2.txt"
    val item_data_full = base + "item_data_full.txt"
    val purchases_full = base + "purchases_full.txt"
    val purchases_main = base + "purchases_main.txt"
    val client_item_name = base + "client_item_name.txt"
    val client_item_data_full = base + "client_item_data_full.txt"
    val item_data = base + "item_data.txt"
    val client_item_data = base + "client_item_data.txt"
    val l = List("NumberInt", "NumberLong", "ISODate", "(", ")")

    val hdfs = args(0)
    val date = args(1)
    val clientId = args(2)
    val interval = args(3)

    val recommender = new Recommender(spark)

    //----------------------------------------ETAPA DE PREPROCESADO---------------------------------------------------//

    recommender.removeNullItemsAndClients(recommender.getBadLines(original_dataset, hdfs), original_dataset, useful_dataset, hdfs)

    val dateLines = recommender.getDateLines(date, interval, useful_dataset, hdfs)
    val lines = recommender.getLines(useful_dataset, hdfs)
    val finalLines = recommender.getFinalLines(lines, dateLines)

    recommender.getFinalDataset(useful_dataset, window_dataset, finalLines, hdfs)
    recommender.addCommas(window_dataset, final_dataset, hdfs)

    for( a<-l ) {
      recommender.strip(final_dataset, auxiliary_dataset, a, hdfs)

      val input = Source.fromInputStream(recommender.readHDFS(auxiliary_dataset, hdfs)).getLines()
      val output = recommender.writeHDFS(final_dataset, hdfs)
      val w1 = new PrintWriter(output)

      input.foreach(x => w1.println(x))
      w1.close()
    }

    recommender.formatDate(final_dataset, clean_json, hdfs) 
    recommender.clientList(recommender.getDF(clean_json, hdfs), clients, hdfs)
    recommender.removeDuplicatesFromFile(clients, hdfs)
    recommender.getItems(clients, recommender.getDF(clean_json, hdfs), purchases_full, purchases_main, hdfs)
    recommender.productList(purchases_main, items, hdfs)
    recommender.averagePrice(items, auxiliary_item_data_full1, hdfs)
    recommender.setProductId(auxiliary_item_data_full1, item_data, 100000, hdfs)
    recommender.groupItemsByPurchase_User(clients, purchases_main, client_item_name, hdfs)
    recommender.replaceProductName(auxiliary_item_data_full1, client_item_name, client_item_data_full, hdfs)
    recommender.groupItemsByItem_User(client_item_data_full, client_item_data, hdfs)
    recommender.getUnitsSold_Incomes(client_item_data_full, auxiliary_item_data_full1, auxiliary_item_data_full2, hdfs)
    recommender.getTimesPurchased(client_item_data, auxiliary_item_data_full2, item_data_full, hdfs)

    //----------------------------------------------ETAPA DE ANÁLISIS-------------------------------------------------//

    recommender.getMostXItems(item_data_full, "popularity", hdfs)
    recommender.getMostXItems(item_data_full, "units", hdfs)
    recommender.getMostXItems(item_data_full, "income", hdfs)

    val rawClientItemData = spark.read.textFile(hdfs + base + "client_item_data.txt")
    val rawItemData = spark.read.textFile(hdfs + base + "item_data.txt")

    if (recommender.checkClient(clients, clientId, hdfs)) {
      recommender.preparation(rawClientItemData, rawItemData)
      recommender.recommend(rawClientItemData, rawItemData, clientId)
      println("HE ACABADO LA RECOMENDACION!!!!!!!!!!!!!!!")
    } else {
      println("El usuario introducido no ha hecho ninguna compra en el intervalo de tiempo especificado")
      println("Compruebe la lista de clientes y pruebe con un usuario válido")
    }
  }
}

class Recommender(private  val spark: SparkSession) {

  import spark.implicits._

  def readHDFS(path: String, hdfs: String): InputStream = {
    val conf = new Configuration()
    conf.set("fs.defaultFS", hdfs)
    val fs = FileSystem.get(conf)
    val input = fs.open(new Path(path)).getWrappedStream
    input
  }

  def writeHDFS(path: String, hdfs: String): OutputStream = {
    val conf = new Configuration()
    conf.set("fs.defaultFS", hdfs)
    val fs = FileSystem.get(conf)
    val output = fs.create(new Path(path)).getWrappedStream
    output
  }

  def getDF(fileIn: String, hdfs: String): DataFrame = {
    val jsonRDD = spark.sparkContext.wholeTextFiles(hdfs+fileIn).map(x => x._2)
    val datos = spark.read.json(jsonRDD)
    datos
  }

  def checkClient(fileIn: String, id: String, hdfs: String): Boolean = {
    var a = false
    for (client <- Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()) {
      if (client.contains(id)) a = true
    }
    a
  }

  def getMostXItems(fileIn: String, feature: String, hdfs: String) = {

    val hashMapI = new HashMap[String, Double]()
    var totalIncome = 0.0

    for (item <- Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()) {
      hashMapI.put(item.split("     ")(1), item.split("     ")(4).toDouble)
      totalIncome += item.split("     ")(4).toDouble
    }

    if (feature.equals("popularity")) {

      println("------------- ITEMS MÁS POPULARES (que más veces se han vendido) -------------")
      println("")

      val hashMap = new HashMap[String, Int]()
      val most = new HashMap[String, Int]()

      for (item <- Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()) {
        hashMap.put(item.split("     ")(1), item.split("     ")(5).toInt)
      }

      for (n <- 0 to 4) {
        val max = hashMap.values.max
        for (item <- hashMap) {
          if (item._2 == max) {
            most.put(item._1, item._2)
            println(item._1 + "  -  " + item._2 + "     " + hashMapI.get(item._1).get +
              "  (" + "%2.2f".format(hashMapI.get(item._1).get/totalIncome*100) + " %)")
            hashMap.remove(item._1)
          }
        }
      }

      println("")
      println("------------------------------------------------------------------------------")
      println("")

    } else if (feature.equals("units")) {

      println("------------ ITEMS MÁS VENDIDOS (que más unidades se han vendido) ------------")
      println("")

      val hashMap = new HashMap[String, Int]()
      val most = new HashMap[String, Int]()

      for (item <- Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()) {
        hashMap.put(item.split("     ")(1), item.split("     ")(3).toInt)
      }

      for (n <- 0 to 4) {
        val max = hashMap.values.max
        for (item <- hashMap) {
          if (item._2 == max) {
            most.put(item._1, item._2)
            println(item._1 + "  -  " + item._2 + "     " + hashMapI.get(item._1).get +
              "  (" + "%2.2f".format(hashMapI.get(item._1).get/totalIncome*100) + " %)")
            hashMap.remove(item._1)
          }
        }
      }

      println("")
      println("------------------------------------------------------------------------------")
      println("")

    } else {

      println("------------- INGRESOS TOTALES (de todas las unidades vendidas) --------------")
      println("")
      println(totalIncome)
      println("")

      println("------------ ITEMS MÁS VALIOSOS (que más ingresos han generado) --------------")
      println("")

      val most = new HashMap[String, Double]()

      for (n <- 0 to 4) {
        val max = hashMapI.values.max
        for (item <- hashMapI) {
          if (item._2 == max) {
            most.put(item._1, item._2)
            println(item._1 + "  -  " + item._2 + "  (" + "%2.2f".format(item._2/totalIncome*100) + " %)")
            hashMapI.remove(item._1)
          }
        }
      }
      println("")
      println("------------------------------------------------------------------------------")
      println("")
    }

  }

  def getTimesPurchased(fileIn1: String, fileIn2: String, fileOut: String, hdfs: String) = {
    val hashMap = new HashMap[Int, Int]()
    val input1 = Source.fromInputStream(readHDFS(fileIn1, hdfs)).getLines()
    val input2 = Source.fromInputStream(readHDFS(fileIn2, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    for (item <- input1) {
      val id = item.split(" ")(1).toInt
      val times = item.split(" ")(2).toInt

      if (hashMap.contains(id)) {
        val oldTimes = hashMap.get(id).get
        hashMap.put(id, oldTimes+times)
      } else {
        hashMap.put(id, times)
      }
    }

    input2
      .map { x =>
        x+"     "+hashMap.get(x.split("     ")(0).toInt).get
      }.foreach(x => w.println(x))
    w.close()
  }

  def getUnitsSold_Incomes(fileIn1: String, fileIn2: String, fileOut: String, hdfs: String) = {
    val hashMapU = new HashMap[Int, Int]()
    val hashMapI = new HashMap[Int, Double]()
    val input1 = Source.fromInputStream(readHDFS(fileIn1, hdfs)).getLines()
    val input2 = Source.fromInputStream(readHDFS(fileIn2, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    for (item <- input1) {
      val id = item.split("     ")(1).toInt
      val units = item.split("     ")(2).toInt
      val income = item.split("     ")(3).toDouble

      if (hashMapU.contains(id)) {
        val oldUnits = hashMapU.get(id).get
        val oldIncome = hashMapI.get(id).get

        hashMapU.put(id, oldUnits+units)
        hashMapI.put(id, oldIncome+income)
      } else {
        hashMapU.put(id, units)
        hashMapI.put(id, income)
      }
    }

    input2
      .map { x =>
        x+"     "+hashMapU.get(x.split("     ")(0).toInt).get+
          "     "+"%2.2f".format(hashMapI.get(x.split("     ")(0).toInt).get).replace(",", ".")
        }.foreach(x => w.println(x))

    w.close()
  }

  def groupItemsByItem_User(fileIn: String, fileOut: String, hdfs: String) = {
    val hashMap = new HashMap[String, Int]()
    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    for (item <- input) {
      val client_item = item.split("     ")(0)+" "+item.split("     ")(1)
      if (hashMap.contains(client_item)) {
        val value = hashMap.get(client_item).get
        hashMap.put(client_item, value+1)
      } else hashMap.put(client_item, 1)
    }

    for (client_item <- hashMap) {
      writer.write(client_item._1+" "+client_item._2)
      writer.write("\n")
    }
    writer.close()
  }

  def averagePrice(fileIn: String, fileOut: String, hdfs: String) = {

    var itemPrice = new HashMap[String, String]()
    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    for (i <- input) {
      val name = i.split("     ")(0)
      val price = i.split("     ")(1)
      if (!itemPrice.contains(name)) {
        itemPrice.put(name, "///"+price)
      } else {
        val oldPrice = itemPrice.get(name)
        itemPrice.put(name, oldPrice.get+"///"+price)
      }
    }

    for (i <- itemPrice) {
      val length = i._2.split("///").length
      var price = 0.0
      for (j <- 1 to length-1) {
        price = price + i._2.split("///")(j).toDouble
      }
      val p = "%2.2f".format(price/(length-1))
      writer.write(i._1+"     "+p.replace(",", "."))
      writer.write("\n")
    }
    writer.close()
  }

  def addCommas(fileIn:String, fileOut:String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    input
      .map { x =>
        if(x.equals("}")) x.replace("}", "},")
        else x
      }.foreach(x => w.println(x))
    w.close()
  }

  def getLines(fileIn: String, hdfs: String): HashMap[Int, Int] = {
    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    var hashMap = new HashMap[Int, Int]()
    var start = 0
    var n = 0
    for (i <- input) {
      if (i.equals("[{") || i.equals("{") || i.equals("[{ ") || i.equals("{ ")) start = n
      else if (i.equals("}]") || i.equals("}")) {
        hashMap.put(start,n)
      }
      n += 1
    }
    hashMap
  }

  def getFinalLines(hashMap: HashMap[Int, Int], hashSet: HashSet[Int]): HashMap[Int, Int] = {

    val finalHashMap = new HashMap[Int, Int]()

    for (i <- hashSet) {
      var y = 0
      for (j <- hashMap) {
        if (i>j._1 && i<j._2) {
          finalHashMap.put(j._1, j._2)
        }
      }
    }
    finalHashMap
  }

  def getFinalDataset(fileIn: String, fileOut: String, hashMap: HashMap[Int, Int], hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    var n = 0
    val m = hashMap.keySet.min
    val M = hashMap.values.max

    for (i <- input) {
      for (j <- hashMap) {
        if (n>=j._1 && n<=j._2) {
          if (n.equals(m)) writer.write("[{")
          else if (n.equals(M)) writer.write("}]")
          else writer.write(i)
          writer.write("\n")
        }
      }
      n += 1
    }
    writer.close()
  }

  def getDateLines(date: String, interval: String, fileIn: String, hdfs: String): HashSet[Int] = {

    var n = 0
    var l = new HashSet[Int]()
    var lastClient = 0
    val int = (interval.toInt-1)*(-1)
    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()

    val format = new java.text.SimpleDateFormat("yyyy-MM-dd")
    val d = format.parse(date)
    val c = Calendar.getInstance()

    for (i <- input) {
      if (i.contains("date")) {
        for (j <- int to 0) {
          c.setTime(d)
          c.add(Calendar.DAY_OF_MONTH, j)
          if (i.contains(format.format(c.getTime))) {
            l.add(n)
          }
        }
      }
      n += 1
    }
    l
  }

  def getBadLines(fileIn: String, hdfs: String): HashSet[Int] = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()

    var n = 0
    var l = new HashSet[Int]()
    var lastClient = 0

    for (i <- input) {
      if (i.contains("None")) {
        for (a <- n-3 to n+3)
          l.add(a)
      }

      if (i.contains("client") && !i.contains("null")) {
        lastClient = n
      }

      if (i.contains("null")) {
        for (a <- lastClient+2 to n+1) {
          l.add(a)
        }
      }

      if (i.contains("a_data") || i.contains("DtoMV") || i.contains("n_a_data")) {
        l.add(n)
      }

      n += 1
    }
    l
  }

  def removeNullItemsAndClients(list: HashSet[Int], fileIn: String, fileOut: String, hdfs: String) = {

    var n = 0
    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    for (i <- input) {
      if(list.contains(n)) {
        n += 1
      }
      else {
        n += 1
        writer.write(i)
        writer.write("\n")
      }
    }
    writer.close()
  }

  def strip(fileIn: String, fileOut: String, substr: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    input
      .map { x =>
        if(x.contains(substr)) x.replace(substr, "")
        else x
      }.foreach(x => w.println(x))
    w.close()
  }

  def formatDate(fileIn: String, fileOut: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    input
      .map { x =>
        if(x.contains("date")) {
          x.substring(0, 24) + """","""
        } else x
      }.foreach(x => w.println(x))
    w.close()
  }

  def fillNullClients(fileIn: String, fileOut: String) = {

    val w = new PrintWriter(new File(fileOut))
    var lastClient = ""

    Source.fromFile(fileIn).getLines
      .map { x =>
        if(x.contains("client")) {
          if(x.contains("null")){
            x.substring(0, 15) + lastClient
          } else {
            lastClient = x.substring(15, 26)
            x
          }
        } else x
      }.foreach(x => w.println(x))
    w.close()
  }

  def clientList(df: DataFrame, fileOut: String, hdfs: String) = {

    val clients = df.select("client").collectAsList()

    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    for (i <- 0 to clients.size()-1) {
      val client = clients.get(i).toString().replace("[", "").replace("]", "")
      writer.write(client+" "+client.toLong.hashCode())
      writer.write("\n")
    }
    writer.close()
  }

  def getItems(fileIn: String, df: DataFrame, fileOut1: String, fileOut2: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val clients = new HashSet[String]()

    for (i <- input) {
      if (i != null) clients.add(i.split(" ")(0))
    }

    val output1 = writeHDFS(fileOut1, hdfs)
    val writer1 = new PrintWriter(output1)
    val output2 = writeHDFS(fileOut2, hdfs)
    val writer2 = new PrintWriter(output2)

    for (client <- clients) {
      val purchases = df.filter(df("client").equalTo(client)).select("items").collectAsList()

      val _id = df.filter(df("client").equalTo(client)).select("_id").collectAsList()
      val date = df.filter(df("client").equalTo(client)).select("date").collectAsList()
      val mall = df.filter(df("client").equalTo(client)).select("mall").collectAsList()

      for (j <- 0 to purchases.size()-1) {

        val purchase = purchases.get(j).getList(0)

        for (i <- 0 to purchase.size()-1) {
          val items = purchase.get(i).toString().replace("[","").replace("]","").split(",")

          var name = ""
          var n = ""

          if(items.length == 7) {
            name = items(0)+"."+items(1)+"."+items(2)+"."+items(3)
            n = items(5)+"     "+items(6)
          } else if (items.length == 6) {
            name = items(0)+"."+items(1)+"."+items(2)
            n = items(4)+"     "+items(5)
          } else if (items.length == 5) {
            name = items(0)+"."+items(1)
            n = items(3)+"     "+items(4)
          } else if (items.length == 4){
            name = items(0)
            n = items(2)+"     "+items(3)
          } else {
            name = items(0)
            n = items(1)+"     "+items(2)
          }

          if (!n.split("     ")(0).equals("0")) {
            writer1.write(client+"     "+_id.get(j).get(0)+"     "+name+"     "+n+"     "+date.get(j).get(0)+"     "+mall.get(j).get(0))
            writer1.write("\n")

            writer2.write(client+"     "+_id.get(j).get(0)+"     "+name+"     "+n)
            writer2.write("\n")
          }
        }
      }
    }
    writer1.close()
    writer2.close()
  }

  def productList(fileIn: String, fileOut: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val w = new PrintWriter(output)

    input
      .map { x => x.split("     ")(2)+"     "+(x.split("     ")(x.split("     ").length-1).toDouble/(x.split("     ")(x.split("     ").length-2)).toInt)
      }.foreach(x => w.println(x))
    w.close()
  }

  def removeDuplicatesFromFile(fileIn: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val lines = new HashSet[String]()

    for (i <- input) {
      if (i != null) lines.add(i)
    }

    val output = writeHDFS(fileIn, hdfs)
    val writer = new PrintWriter(output)
    for (unique <- lines) {
      if(unique != "()") {
        writer.write(unique)
        writer.write("\n")
      }
    }
    writer.close()
  }

  def setProductId(fileIn: String, fileOut: String, idInit: Integer, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn, hdfs)).getLines()
    val lines = new HashSet[String]()

    for (i <- input) {
      if (i != null) lines.add(i)
    }

    val output1 = writeHDFS(fileIn, hdfs)
    val writer1 = new PrintWriter(output1)
    val output2 = writeHDFS(fileOut, hdfs)
    val writer2 = new PrintWriter(output2)

    var a = idInit
    for (unique <- lines) {
      writer1.write(a+"     "+unique)
      writer1.write("\n")

      writer2.write(a+"     "+unique.split("     ")(0))
      writer2.write("\n")

      a += 1
    }
    writer1.close()
    writer2.close()
  }

  def groupItemsByPurchase_User(fileIn1: String, fileIn2: String, fileOut: String, hdfs: String) = {

    val clients = Source.fromInputStream(readHDFS(fileIn1, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)

    for (client <- clients) {

      val c = client.split(" ")(0) 
      val lines = new HashMap[String, String]()

      for (item <- Source.fromInputStream(readHDFS(fileIn2, hdfs)).getLines().filter(_.contains(c))) {
        val i = item.split("     ") 
        var t = ""
        var n = 0
        var p = 0.0

        if (i.length == 6) {
          t = c+"     "+i(1)+"     "+i(2)+"     "+i(3)
          n = i(4).replace(" ","").toInt
          p = i(5).replace(" ","").toDouble
        } else {
          t = c+"     "+i(1)+"     "+i(2)
          n = i(3).replace(" ","").toInt
          p = i(4).replace(" ","").toDouble
        }
        
        if (lines.contains(t)) {
          
          val oldN = lines.get(t).get.split("     ")(0).toInt
          val oldP = lines.get(t).get.split("     ")(1).toDouble
          
          lines.put(t, (n+oldN)+"     "+"%2.2f".format(p+oldP).toString.replace(",", "."))
        }
        else lines.put(t, n+"     "+p)
      }

      for (unique <- lines) {
        writer.write(unique._1+"     "+unique._2)
        writer.write("\n")
      }
    }
    writer.close()
  }

  def replaceProductName(fileIn1: String, fileIn2: String, fileOut: String, hdfs: String) = {

    val input = Source.fromInputStream(readHDFS(fileIn1, hdfs)).getLines()
    val output = writeHDFS(fileOut, hdfs)
    val writer = new PrintWriter(output)
    var name = ""

    for (item <- input) {

      if (item.split("     ").length == 4) name = item.split("     ")(1)+"     "+item.split("     ")(2)
      else name = item.split("     ")(1)

      for (purchase <- Source.fromInputStream(readHDFS(fileIn2, hdfs)).getLines()) {
        if (purchase.contains("     "+name+"     ")) {
          val r = purchase.split("     ")(1)+"     "+name+"     "
          writer.write(purchase.replace(r, item.split("     ")(0)+"     "))
          writer.write("\n")
        }
      }
    }
    writer.close()
  }

  def preparation(rawClientItemData: Dataset[String], rawItemData: Dataset[String]): Unit = {

    val clientItemDF = rawClientItemData.map { line =>
      val Array(client, item, _*) = line.split(" ")
      (client.toLong, item.toInt)
    }.toDF("client", "item")

    clientItemDF.agg(min("client"), max("client"), min("item"), max("item")).show()

    val itemByID = buildItemByID(rawItemData)
    itemByID.show()
    println("HE ACABADO LA PREPARACIÓN!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
  }

  def recommend(
                 rawClientItemData: Dataset[String],
                 rawItemData: Dataset[String], ID: String): Unit = {

    val allData = buildCounts(rawClientItemData).cache()
    val model = new ALS().
      setSeed(Random.nextLong()).
      setImplicitPrefs(true).
      setRank(10).setRegParam(1.0).setAlpha(40.0).setMaxIter(20).
      setUserCol("client").setItemCol("item").
      setRatingCol("count").setPredictionCol("prediction").
      fit(allData)
    allData.unpersist()

    val clientID = ID.toLong.hashCode()
    val topRecommendations = makeRecommendations(model, clientID, 5)

    val recommendedItemIDs = topRecommendations.select("item").as[Int].collect()
    val itemByID = buildItemByID(rawItemData)
    itemByID.join(spark.createDataset(recommendedItemIDs).toDF("id"), "id").
      select("name").show()

    model.userFactors.unpersist()
    model.itemFactors.unpersist()
  }

  def buildItemByID(rawItemData: Dataset[String]): DataFrame = {
    rawItemData.flatMap { line =>
      val Array(id, name) = line.split("     ")
      if (name.isEmpty) {
        None
      } else {
        try {
          Some((id.toInt, name.trim))
        } catch {
          case _: NumberFormatException => None
        }
      }
    }.toDF("id", "name")
  }

  def buildCounts(rawClientItemData: Dataset[String]): DataFrame = {
    rawClientItemData.map { line =>
      val ls = line.split(" ")
      (ls(0).toLong.hashCode(), ls(1).toInt, ls(2).toInt)
    }.toDF("client", "item", "count")
  }

  def makeRecommendations(model: ALSModel, clientID: Int, howMany: Int): DataFrame = {
    val toRecommend = model.itemFactors.
      select($"id".as("item")).
      withColumn("client", lit(clientID))
    model.transform(toRecommend).
      select("item", "prediction").
      orderBy($"prediction".desc).
      limit(howMany)
  }

}
