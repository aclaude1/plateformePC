package io.vertx.blog.plateforme;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.file.FileSystem;
import io.vertx.core.MultiMap;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.FileUpload;

import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.FindOptions;

import java.util.Set;
import java.util.Iterator;
import java.util.Map;
import java.util.List;

import java.lang.Process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.InterruptedException;


public class VerticlePlateforme extends AbstractVerticle{

    //Chemin absolu de l'application
    private final String pathApp = new File(".").getAbsolutePath();

    //Connexion BD
    MongoClient mongoClient;

    /**La méthode start est appelée quand un verticle est déployé
    On peut aussi impmémenter une méthode stop()
    L'objet Future qui informe Vert.X si la méthode start est finie ou non:
    Vert.X est ayschrone, quand un verticle est déployé, il attend pas que les
    traitements de la méthode start soit finie. L'objet Future permet de savoir
    si ces traitements sont réalisés ou non
    */

    @Override
    public void start (Future<Void> fut){
        //Creation de l'objet Router (permettant les routes http)
        Router router = Router.router(vertx);

        //Connexion à la Base de donnée
        JsonObject configuration = new JsonObject();
        configuration.put("http.port",8082);
        configuration.put("db_name","plateforme");
        configuration.put("connection_string","mongodb://localhost:27017");
        mongoClient = MongoClient.createNonShared(vertx, configuration);


        /**L'étoile dans la route est pour attraper toutes les routes en /asserts/
        La route statique permet de toujours lier ces routes au répertoire asserts
        */
        router.route("/ressources/*").handler(StaticHandler.create("ressources"));

        /** L'utilisation d'un BodyHandler est primordiale:
        1/ Ca permet d'avoir acces aux body des requetes (pour retrouver
        les parametres des methodes POST,PUT et DELETE)
        2/ Ca donne accès aux Fichier uploadés **/
        router.route("/api*").handler(BodyHandler.create("FilesUploaded"));
        /** Un blockingHandler est comme un handler mais permet de faire des
        traitements plus long, sans bloquer l'eventloop */
        router.post("/api/postfile").blockingHandler(this::file);
        router.get("/api/getallexo").blockingHandler(this::getAllExo);
        router.get("/api/getoneexo").blockingHandler(this::getOneExo);
        router.get("/api/gettagsexo").blockingHandler(this::getTagsExo);
        router.get("/api/gettags").blockingHandler(this::getTags);

        router.post("/api/postexo").blockingHandler(this::postExo);

        router.delete("/api/delexo").blockingHandler(this::delExo);

        /** On crée le server HTTP. Il faut passer le router dans l'écouter de requête
        De plus, nous devons spécifer que les requête sur ce router sont acceptées
        */
        vertx
        .createHttpServer()
        .requestHandler(router::accept)
        .listen(8080, result -> {
            if (result.succeeded()){
                fut.complete();
            }else{
                fut.fail(result.cause());
            }
        });
    }

    private void file(RoutingContext routingContext) {
        //Récupération des parametres de l'exercice
        String exercice = routingContext.request().getParam("exo");
        System.out.println(exercice);

        //Déclaration d'un FileSystem
        FileSystem fileSys = vertx.fileSystem();
        Set<FileUpload> setFile = routingContext.fileUploads();
        Iterator<FileUpload> it = setFile.iterator();

        /* CREATION DU REPERTOIRE ETUDIANT UNIQUE
        On récupére le nom du premier fichier reçu (qui est un String aléatoire)
        Le répéroire etudiant prend ce nom là
        */

        FileUpload fu = it.next();
        // Chemin complet du fichier dans le serveur (nouveau)
        String serveurFilePath = fu.uploadedFileName();
        // Création du répértoire client
        String repEtudiant = "exercices/"+exercice+"/"+getFileName(serveurFilePath);
        fileSys.mkdirBlocking(repEtudiant);

        // Nom du fichier original (dans le repértoire client)
        String originalName = fu.fileName();
        //Copie du ficher dans le nouveau répertoire, avec le bon nom
        String newFilePath = repEtudiant+"/"+originalName;
        fileSys.copyBlocking(serveurFilePath,newFilePath);
        fileSys.deleteBlocking(serveurFilePath);

        /** Gestion du cas ou plusieurs fichiers sont demandés
        On parcours le set de <FileUpload>
        On ajoute les fichiers dans le répertoire etudiant */
        while(it.hasNext()){
            fu = it.next();
            originalName = fu.fileName();
            serveurFilePath = fu.uploadedFileName();
            //Copie du ficher dans le nouveau répertoire, avec le bon nom
            newFilePath = repEtudiant+"/"+originalName;
            fileSys.copyBlocking(serveurFilePath,newFilePath);
            fileSys.deleteBlocking(serveurFilePath);
        }

        String commande = "docker run --rm -v "+pathApp+"/"+repEtudiant+":/java/etudiant plateforme:"+exercice;
        System.out.println(commande);
        try{
            // Execution du composant docker
            Process proc=Runtime.getRuntime().exec(commande);
            // IMPORTANT !!! Permet d'attendre la fin de l'execution du composant docker
            proc.waitFor();
        } catch(InterruptedException e){
            System.out.println(e.toString());
            String result = e.toString();
        } catch(IOException e){
            System.out.println(e.toString());
            String result = e.toString();
        }

        String str = readFile(repEtudiant+"/driver_result.txt");
        routingContext.response()
        .setStatusCode(200)
        .putHeader("content-type", "text/html; charset=utf-8")
        .end(str);

        //Suppression du répertoire client
        fileSys.deleteRecursiveBlocking(repEtudiant,true);
    }

    /** Récupérer tout les exercices de la BD */
    private void getAllExo(RoutingContext routingContext){
        JsonObject query = new JsonObject();
        mongoClient.find("Exercices", query, res -> {
            if (res.succeeded()) {

                JsonArray listExo = new JsonArray(res.result());
                routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(listExo.encodePrettily());
            } else {
                res.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "text/txt; charset=utf-8")
                .end("ERROR");
            }
        });
    }

    /** Récupérer un exercice dans la base **/
    private void getOneExo(RoutingContext routingContext){
        String nomExoRepertoire = routingContext.request().getParam("nom");
        JsonObject query = new JsonObject().put("nomExoRepertoire",nomExoRepertoire);

        mongoClient.findBatch("Exercices", query, res -> {

            if (res.succeeded()) {

                routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(res.result().encodePrettily());

            } else {

                res.cause().printStackTrace();
                res.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "text/txt; charset=utf-8")
                .end("Exercice non trouvé");
            }
        });
    }

    /* Récupérer les exos de la BD par tags */
    private void getTagsExo(RoutingContext routingContext){
        // Récupération des paramétres de la requete client
        MultiMap paramsMap = routingContext.request().params();
        JsonArray listTags = new JsonArray(paramsMap.getAll("tags"));
        int difficulteMin = Integer.parseInt(paramsMap.get("dif_min"));
        int difficulteMax = Integer.parseInt(paramsMap.get("dif_max"));

        //Création de la requete mongodb
        JsonArray queryArray = new JsonArray();
        queryArray.add(new JsonObject().put("tags", new JsonObject().put("$in",listTags)));
        queryArray.add(new JsonObject().put("difficulte",new JsonObject().put("$gte",difficulteMin)));
        queryArray.add(new JsonObject().put("difficulte",new JsonObject().put("$lte",difficulteMax)));
        JsonObject query = new JsonObject().put("$and",queryArray);

        //Execution de la requete mongodb
        mongoClient.find("Exercices", query, res -> {

            if (res.succeeded()) {

                JsonArray listExo = new JsonArray(res.result());
                routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(listExo.encodePrettily());
            } else {
                res.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "text/txt; charset=utf-8")
                .end("ERROR");
            }
        });
    }

    /* Récupérer les tags de la BD de façon UNIQUE*/
    private void getTags(RoutingContext routingContext){

        JsonObject query = new JsonObject();
        mongoClient.find("Exercices", query, res -> {
            if (res.succeeded()) {
                //Déclaration de la JsonArray qui sera renvoyée
                JsonArray listTags = new JsonArray();
                //Paroucours de tout les Exercices de la BD
                for(JsonObject exo : res.result()){
                    JsonArray listTagsExo = exo.getJsonArray("tags");
                    //Paroucours des tags d'un Exo
                    for(int i=0;i<listTagsExo.size();i++){
                        String strTags = listTagsExo.getString(i);
                        //Ajout dans la list des tags SSI n'y figure pas encore
                        if(!listTags.contains(strTags)){
                            listTags.add(strTags);
                        }
                    }
                }

                //Renvoie de la liste en JSON
                routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(listTags.encodePrettily());
            } else {
                res.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "text/txt; charset=utf-8")
                .end("ERROR");
            }
        });

    }

    private void postExo(RoutingContext routingContext){

        //Déclaration d'un FileSystem
        FileSystem fileSys = vertx.fileSystem();
        Set<FileUpload> setFile = routingContext.fileUploads();
        Iterator<FileUpload> it = setFile.iterator();

        /** RECUPERATION DES PARAMERES REQUETES **/
        //Nom de l'exercice
        String nomExo = routingContext.request().getParam("nom");
        /**Création du nom qui sera utilisé pour le repertoire et docker
        Ce nom est le nom de l'exercice auquel on a remplacer les char spéciaux pas des _ */
        String nomExoRepertoire = nomExo.replaceAll("\\W","_");

        //Tags de l'exercice
        //Ancien tags
        MultiMap paramsMap = routingContext.request().params();
        JsonArray listTags = new JsonArray(paramsMap.getAll("ancienTags"));
        //nouveau tags
        String oldtags = routingContext.request().getParam("nouveauTags");
        JsonArray listOldTags = makeJsonArray(oldtags);
        //fusion des deux array
        for(int i=0;i<listOldTags.size();i++){
            String strTags = listOldTags.getString(i);
            listTags.add(strTags);
        }

        //pres_requis
        String pres_requis = routingContext.request().getParam("pre_requis");
        JsonArray listPres_requis = makeJsonArray(pres_requis);
        //objectifs
        String objectifs = routingContext.request().getParam("objectif");
        JsonArray listObjectifs = makeJsonArray(objectifs);
        //difficulte de l'exo
        int difficulte = Integer.parseInt(routingContext.request().getParam("difficulte"));
        //Nom des sources que doit rendre l'étudiant
        String rendus = routingContext.request().getParam("rendus");
        JsonArray listRendus = makeJsonArray(rendus);

        /** TEST SI L'EXERCICE EXISTE DEJA **/
        JsonObject query = new JsonObject().put("nomExoRepertoire",nomExoRepertoire);
        mongoClient.findBatch("Exercices", query, res1 -> {
            if (res1.succeeded()) {
                if (res1.result() == null) {

                    //L'exercice n'est pas présent, on continue la requête
                    // Création d'un répertoire pour cet exercice
                    String repExercice = "exercices/"+nomExoRepertoire;
                    String repRessource = "ressources/"+repExercice;
                    //Création des répertoires
                    fileSys.mkdirBlocking(repExercice);
                    fileSys.mkdirBlocking(repRessource);

                    /** Déplacement des fichers dans le repertoire adéquat*/
                    while(it.hasNext()){
                        FileUpload fu = it.next();
                        //Nom du fichier dans le formulaire
                        String originalName = fu.name();
                        //Chemin du fichier sur la plateforme
                        String serveurFilePath = fu.uploadedFileName();
                        String newFilePath;
                        if(originalName.equals("drivers.zip")){
                            //Si on a affaire au driver, va dans le rep exercice
                            newFilePath = repExercice+"/"+originalName;
                        } else {
                            //Sinon, on met le tout dans le rep ressources
                            newFilePath = repRessource+"/"+originalName;
                        }
                        fileSys.copyBlocking(serveurFilePath,newFilePath);
                        fileSys.deleteBlocking(serveurFilePath);
                    }

                    //Création de l'entrée dans la base de données
                    JsonObject exercice = new JsonObject();
                    exercice.put("nom",nomExo);
                    exercice.put("nomExoRepertoire",nomExoRepertoire);
                    exercice.put("tags",listTags);
                    exercice.put("pres_requis",listPres_requis);
                    exercice.put("objectifs",listObjectifs);
                    exercice.put("difficulte",difficulte);
                    exercice.put("rendus",listRendus);

                    mongoClient.insert("Exercices", exercice, res -> {

                        if (res.succeeded()) {
                            //Création commandes System
                            String commande = "unzip "+repExercice+"/drivers.zip -d "+repExercice+"/";
                            String commande2 = "docker build -t plateforme:"+nomExoRepertoire+" --build-arg driver_dir="+repExercice+"/drivers .";
                            try{
                                // Execution commandes System
                                Process proc=Runtime.getRuntime().exec(commande);
                                Process proc2=Runtime.getRuntime().exec(commande2);
                                //Synchonisation
                                proc.waitFor();
                                proc2.waitFor();
                            } catch(InterruptedException e){
                                System.out.println(e.toString());
                                String result = e.toString();
                            } catch(IOException e){
                                System.out.println(e.toString());
                                String result = e.toString();
                            }


                            routingContext.response()
                            .setStatusCode(200)
                            .putHeader("content-type", "html/text; charset=utf-8")
                            .end("Exercice enregistré");

                        } else {
                            routingContext.response()
                            .setStatusCode(401)
                            .putHeader("content-type", "html/text; charset=utf-8")
                            .end("ERROR");
                        }
                    });

                } else {
                    //On s'arrêtre tout de suite
                    String err = "Exercice déjà présent";
                    System.out.println(err);
                    routingContext.response()
                    .setStatusCode(401)
                    .putHeader("content-type", "html/text; charset=utf-8")
                    .end(err);
                }
            } else {
                //ERREUR DB
                res1.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "html/text; charset=utf-8")
                .end("ERROR");
            }
        });


    }

    private void delExo(RoutingContext routingContext){
        FileSystem fileSys = vertx.fileSystem();
        String nomExo = routingContext.request().getParam("exo");
        String nomExoRepertoire = nomExo.replaceAll("\\W","_");
        String repExo="exercices/"+nomExoRepertoire;
        String repRessource = "ressources/"+repExo;
        JsonObject query = new JsonObject().put("nomExoRepertoire",nomExoRepertoire);

        mongoClient.remove("Exercices", query, res -> {

            if (res.succeeded()) {

                //Création commandes System
                String commande = "docker rmi plateforme:"+nomExoRepertoire;
                try{
                    // Execution commandes System
                    Process proc=Runtime.getRuntime().exec(commande);
                    fileSys.deleteRecursiveBlocking(repExo,true);
                    fileSys.deleteRecursiveBlocking(repRessource,true);
                    //Synchonisation
                    proc.waitFor();
                } catch(InterruptedException e){
                    System.out.println(e.toString());
                    String result = e.toString();
                } catch(IOException e){
                    System.out.println(e.toString());
                    String result = e.toString();
                }

                routingContext.response()
                .setStatusCode(200)
                .putHeader("content-type", "html/text; charset=utf-8")
                .end("Exercice suppimé");
            } else {
                res.cause().printStackTrace();
                routingContext.response()
                .setStatusCode(401)
                .putHeader("content-type", "html/text; charset=utf-8")
                .end("ERROR");
            }
        });

    }


    /** Lecture d'un ficher */
    private String readFile(String file){
        String res="";
        try{
            InputStreamReader ipsr = new InputStreamReader(new FileInputStream(file));
            BufferedReader br=new BufferedReader(ipsr);
            String ligne;
            while ((ligne=br.readLine())!=null){
                System.out.println(ligne);
                res+=ligne+"\n";
            }
            br.close();
        }
        catch (Exception e){
            System.out.println(e.toString());
            res=e.toString();
        }
        return res;
    }

    /** Renvoie le nom du fichier en prennant en entrée son Chemin */
    private String getFileName(String filePath){
        String[] parts = filePath.split("/");
        return parts[1];
    }

    /** Renvoie la JsonArray à partir d'une String
    On va en fait spliter la chaine de caractere sur la virgule
    L'utilisation de la fonction trim() épure les premiers et derniers espaces*/
    private JsonArray makeJsonArray(String str){
        JsonArray res = new JsonArray();
        if(str.indexOf(',')!=-1){
            String[] parts = str.split(",");
            for(String str1 : parts){
                res.add(str1.trim());
            }
        }else{
            res.add(str.trim());
        }
        return res;
    }
}
