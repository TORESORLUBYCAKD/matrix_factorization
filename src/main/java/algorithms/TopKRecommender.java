package algorithms;

import java.util.*;
import java.util.stream.Collectors;

import utils.CommonUtils;
import utils.Printer;
import data_structure.DenseVector;
import data_structure.Rating;
import data_structure.SparseMatrix;
import data_structure.DenseMatrix;
import utils.TopKPriorityQueue;

/**
 * This is an abstract class for topK recommender systems.
 * Define some variables to use, and member functions to implement by a topK recommender.
 *
 * @author HeXiangnan
 * @since 2014.12.03
 */
public abstract class TopKRecommender {
    /**
     * The number of users.
     */
    public int userCount;
    /**
     * The number of items.
     */
    public int itemCount;
    /**
     * Rating matrix of training set. Users by Items.
     */
    public SparseMatrix trainMatrix;
    /**
     * Test ratings. For showing progress only.
     */
    public ArrayList<Rating> testRatings;

    public List<List<Integer>> negativeRatings;

    /**
     * Position to cutoff.
     */
    public int topK = 100;
    /**
     * Number of threads to run the model (if multi-thread implementation).
     */
    public int threadNum = 1;

    /**
     * Evaluation for each user (offline eval) or test instance (online eval).
     */
    public DenseVector hits;
    public DenseVector ndcgs;
    public DenseVector precs;
    public int maxIterOnline = 1;

    public boolean ignoreTrain = false; // ignore train items when generating topK list

    public TopKRecommender() {
    }

    public TopKRecommender(SparseMatrix trainMatrix,
                           ArrayList<Rating> testRatings, int topK, int threadNum) {
        this.trainMatrix = new SparseMatrix(trainMatrix);
        this.testRatings = new ArrayList<Rating>(testRatings);
        this.topK = topK;
        this.threadNum = threadNum;

        this.userCount = trainMatrix.length()[0];
        this.itemCount = trainMatrix.length()[1];
    }

    public TopKRecommender(SparseMatrix trainMatrix,
                           ArrayList<Rating> testRatings,
                           List<List<Integer>> negativeRatings, int topK, int threadNum) {
        this.trainMatrix = new SparseMatrix(trainMatrix);
        this.testRatings = new ArrayList<>(testRatings);
        this.negativeRatings = negativeRatings;
        this.topK = topK;
        this.threadNum = threadNum;

        this.userCount = trainMatrix.length()[0];
        this.itemCount = trainMatrix.length()[1];
    }


    /**
     * Get the prediction score of user u on item i. To be overridden.
     */
    public abstract double predict(int u, int i);

    /**
     * Build the model.
     */
    public abstract void buildModel();

    /**
     * Update the model with a new observation.
     */
    public abstract void updateModel(int u, int i);

    /**
     * Show progress (evaluation) with current model parameters.
     *
     * @iter Current iteration
     * @start Starting time of the iteration
     * @testMatrix For evaluation purpose
     */
    public void showProgress(int iter, long start, ArrayList<Rating> testRatings) {
        long end_iter = System.currentTimeMillis();
        if (userCount == testRatings.size())  // leave-1-out eval
            evaluate(testRatings);
        else    // global split
            evaluateOnline(testRatings, 100);
        long end_eval = System.currentTimeMillis();

        System.out.printf("Iter=%d[%s] <loss, hr, ndcg, prec>:\t %.4f\t %.4f\t %.4f\t %.4f\t [%s]\n",
                iter, Printer.printTime(end_iter - start), loss(),
                hits.mean(), ndcgs.mean(), precs.mean(), Printer.printTime(end_eval - end_iter));
    }

    /**
     * Online evaluation (global split) by simulating the testing stream.
     *
     * @param ratings  Test ratings that are sorted by time (old -> recent).
     * @param interval Print evaluation result per X iteration.
     */
    public void evaluateOnline(ArrayList<Rating> testRatings, int interval) {
        int testCount = testRatings.size();
        hits = new DenseVector(testCount);
        ndcgs = new DenseVector(testCount);
        precs = new DenseVector(testCount);

        // break down the results by number of user ratings of the test pair
        int intervals = 10;
        int[] counts = new int[intervals + 1];
        double[] hits_r = new double[intervals + 1];
        double[] ndcgs_r = new double[intervals + 1];
        double[] precs_r = new double[intervals + 1];

        Long updateTime = (long) 0;
        for (int i = 0; i < testCount; i++) {
            // Check performance per interval:
            if (i > 0 && interval > 0 && i % interval == 0) {
                System.out.printf("%d: <hr, ndcg, prec> =\t %.4f\t %.4f\t %.4f\n",
                        i, hits.sum() / i, ndcgs.sum() / i, precs.sum() / i);
            }
            // Evaluate model of the current test rating:
            Rating rating = testRatings.get(i);
            double[] res = this.evaluate_for_user(rating.userId, rating.itemId);
            hits.set(i, res[0]);
            ndcgs.set(i, res[1]);
            precs.set(i, res[2]);

            // statisitcs for break down
            int r = trainMatrix.getRowRef(rating.userId).itemCount();
            r = r > intervals ? intervals : r;
            counts[r] += 1;
            hits_r[r] += res[0];
            ndcgs_r[r] += res[1];
            precs_r[r] += res[2];

            // Update the model
            Long start = System.currentTimeMillis();
            updateModel(rating.userId, rating.itemId);
            updateTime += (System.currentTimeMillis() - start);
        }

        System.out.println("Break down the results by number of user ratings for the test pair.");
        System.out.printf("#Rating\t Percentage\t HR\t NDCG\t MAP\n");
        for (int i = 0; i <= intervals; i++) {
            System.out.printf("%d\t %.2f%%\t %.4f\t %.4f\t %.4f \n",
                    i, (double) counts[i] / testCount * 100,
                    hits_r[i] / counts[i], ndcgs_r[i] / counts[i], precs_r[i] / counts[i]);
        }

        System.out.printf("Avg model update time per instance: %.2f ms\n", (float) updateTime / testCount);
    }

    protected ArrayList<Integer> threadSplit(int total, int threadNum, int t) {
        ArrayList<Integer> res = new ArrayList<Integer>();
        int start = (total / threadNum) * t;
        int end = (t == threadNum - 1) ? total :
                (total / threadNum) * (t + 1);
        for (int i = start; i < end; i++)
            res.add(i);
        return res;
    }

    /**
     * Offline evaluation (leave-1-out) for each user.
     *
     * @param topK       position to cutoff
     * @param testMatrix
     * @throws InterruptedException
     */
    public void evaluate(ArrayList<Rating> testRatings) {
        assert userCount == testRatings.size();
        for (int u = 0; u < userCount; u++)
            assert u == testRatings.get(u).userId;

        hits = new DenseVector(userCount);
        ndcgs = new DenseVector(userCount);
        precs = new DenseVector(userCount);

        // Run the evaluation multi-threads splitted by users
        EvaluationThread[] threads = new EvaluationThread[threadNum];
        for (int t = 0; t < threadNum; t++) {
            ArrayList<Integer> users = threadSplit(userCount, threadNum, t);
            threads[t] = new EvaluationThread(this, testRatings, users);
            threads[t].start();
        }

        // Wait until all threads are finished.
        for (int t = 0; t < threads.length; t++) {
            try {
                threads[t].join();
            } catch (InterruptedException e) {
                System.err.println("InterruptException was caught: " + e.getMessage());
            }
        }
    }

    /**
     * Evaluation for a specific user with given GT item.
     *
     * @return: result[0]: hit ratio
     * result[1]: ndcg
     * result[2]: precision
     */
    protected double[] evaluate_for_user(int u, int gtItem) {
        double[] result = new double[3];

        // create list of items
        if (negativeRatings.size() <= u) {
            System.out.println(u);
        }

        List<Integer> items = negativeRatings.get(u);
        items.add(gtItem);

        Map<Integer, Double> scoreMap = new HashMap<>();
        for (Integer item : items) {
            double score = predict(u, item);
            scoreMap.put(item, score);
        }

        // get top K
        Map<Integer, Double> sortedMap = sortByValue(scoreMap);
        List<Integer> rankList = new ArrayList<>(sortedMap.keySet()).subList(0, topK);

        // Selecting topK items (does not exclude train items).
//        ArrayList<Integer> rankList = ignoreTrain ?
//                CommonUtils.TopKeysByValue(map_item_score, topK, trainMatrix.getRowRef(u).indexList()) :
//                CommonUtils.TopKeysByValue(map_item_score, topK, null);

        result[0] = getHitRatio(rankList, gtItem);
        result[1] = getNDCG(rankList, gtItem);
        result[2] = getPrecision(rankList, gtItem);

        return result;
    }

    /**
     * Compute Hit Ratio.
     *
     * @param rankList A list of ranked item IDs
     * @param gtItem   The ground truth item.
     * @return Hit ratio.
     */
    public double getHitRatio(List<Integer> rankList, int gtItem) {
        for (int item : rankList) {
            if (item == gtItem) return 1;
        }
        return 0;
    }

    /**
     * Compute NDCG of a list of ranked items.
     * See http://recsyswiki.com/wiki/Discounted_Cumulative_Gain
     *
     * @param rankList a list of ranked item IDs
     * @param gtItem   The ground truth item.
     * @return NDCG.
     */
    public double getNDCG(List<Integer> rankList, int gtItem) {
        for (int i = 0; i < rankList.size(); i++) {
            int item = rankList.get(i);
            if (item == gtItem)
                return Math.log(2) / Math.log(i + 2);
        }
        return 0;
    }

    public double getPrecision(List<Integer> rankList, int gtItem) {
        for (int i = 0; i < rankList.size(); i++) {
            int item = rankList.get(i);
            if (item == gtItem)
                return 1.0 / (i + 1);
        }
        return 0;
    }

    // remove
    public void runOneIteration() {
    }

    // remove
    public double loss() {
        return 0;
    }

    // remove
    public void setUV(DenseMatrix U, DenseMatrix V) {
    }

    public static void main(String args[]) {
        Map<String, Integer> map = new HashMap<>();
        map.put("a", 2);
        map.put("b", 1);
        map.put("c", 3);

        Map<String, Integer> topTen = sortByValue(map);
    }

    private static <K, V> Map<K, V> sortByValue(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new LinkedList<>(map.entrySet());
        Collections.sort(list, (o1, o2) -> ((Comparable<V>) o2.getValue()).compareTo(o1.getValue()));

        Map<K, V> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<K, V>> it = list.iterator(); it.hasNext(); ) {
            Map.Entry<K, V> entry = it.next();
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }
}

// Thread for running the offline evaluation.
class EvaluationThread extends Thread {
    TopKRecommender model;
    ArrayList<Rating> testRatings;
    ArrayList<Integer> users;

    public EvaluationThread(TopKRecommender model, ArrayList<Rating> testRatings,
                            ArrayList<Integer> users) {
        this.model = model;
        this.testRatings = testRatings;
        this.users = users;
    }

    public void run() {
        for (int u : users) {
            double[] res = model.evaluate_for_user(u, testRatings.get(u).itemId);
            model.hits.set(u, res[0]);
            model.ndcgs.set(u, res[1]);
            model.precs.set(u, res[2]);
        }
    }
}