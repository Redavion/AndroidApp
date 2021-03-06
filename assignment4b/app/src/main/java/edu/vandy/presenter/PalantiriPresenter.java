package edu.vandy.presenter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import android.content.Intent;
import android.util.Log;
import edu.vandy.R;
import edu.vandy.utils.StreamsUtils;
import edu.vandy.utils.UiUtils;
import edu.vandy.model.PalantiriModel;
import edu.vandy.utils.Options;
import edu.vandy.view.DotArrayAdapter.DotColor;
import edu.vandy.view.GazingSimulationActivity;

import static java.util.stream.Collectors.toList;

/**
 * This class manages the Palantiri simulation.  The simulation begins
 * in the start() method, which is called by the UI Thread and is
 * provided a reference to GazingSimulationActivity, which is used to
 * manipulate the UI.  The Options singleton contains the number of
 * beings to simulate and the number of palantiri to simulate.
 * 
 * The simulation should run as follows: the correct number of
 * palantiri should be instantiated and added to the LeasePool in the
 * Model layer.  A Java thread should be created for each Being.  Each
 * Being thread should attempt to acquire a palantir a certain number
 * of times (defined via the GAZE_ATTEMPTS constant below).  As this
 * is happening, Being threads should call the appropriate methods in
 * GazingSimulationActivity to demonstrate which palantiri are being
 * used and which Beings currently own a palantir.
 *
 * This class plays the "Presenter" role in the Model-View-Presenter
 * (MVP) pattern by acting upon the Model and the View, i.e., it
 * retrieves data from the Model (e.g., PalantiriModel) and formats it
 * for display in the View (e.g., GazingSimulationActivity).
 */
public class PalantiriPresenter {
    /**
     * Used for Android debugging.
     */
    private final static String TAG = 
        PalantiriPresenter.class.getName();

    /**
     * Keeps track of whether a runtime configuration change ever
     * occurred.
     */
    private boolean mConfigurationChangeOccurred;

    /**
     * Used to simplify actions performed by the UI, so the
     * application doesn't have to worry about it.
     */
    WeakReference<GazingSimulationActivity> mView;

    /**
     * The list of Beings (implemented as Callables that concurrently
     * execute in a pool of Java Threads) that are attempting to
     * acquire Palantiri for gazing.
     */
    private List<BeingCallable> mBeingCallables;

    /**
     * The list of futures to Beings that are running concurrently in
     * the ExecutorService's thread pool.
     */
    private List<Future<?>> mFutureList;

    /**
     * The ExecutorService contains a cached pool of threads.
     */
    private ExecutorService mExecutor;

    /**
     * Tracks whether a simulation is currently running or not.
     */
    private boolean mRunning = false;

    /**
     * This List keeps track of how many palantiri we have and whether
     * they're in use or not.
     */
    private List<DotColor> mPalantiriColors =
        new ArrayList<>();
	
    /**
     * This List keeps track of how many beings we have and whether
     * they're gazing or not.
     */
    private List<DotColor> mBeingsColors =
        new ArrayList<>();

    /**
     * This reference points to the PalantiriModel in the Model layer.
     */
    private PalantiriModel mModel;

    /**
     * Constructor called when a new instance of PalantiriPresenter is
     * created.  Initialization code goes here, e.g., storing a
     * WeakReference to the View layer and initializing the Model
     * layer.
     *
     * @param view
     *            A reference to the activity in the View layer.
     */
    public PalantiriPresenter(GazingSimulationActivity view) {
        // Set the WeakReference.
        mView = new WeakReference<>(view);

        // Initialize the model.
        mModel = new PalantiriModel();

        // Get the intent used to start the Activity.
        Intent intent = view.getIntent();

        // Initialize the Options singleton using the extras contained
        // in the intent.
        if (!Options.instance().parseArgs(view,
                                          makeArgv(intent)))
            UiUtils.showToast(view,
                              R.string.toast_incorrect_arguments);

        // A runtime configuration change has not occurred (yet).
        mConfigurationChangeOccurred = false;
    }

    /**
     * Hook method dispatched to reinitialize the PalantiriPresenter
     * object after a runtime configuration change.
     *
     * @param view         
     *          The currently active activity view.
     */
    public void onConfigurationChange(GazingSimulationActivity view) {
        Log.d(TAG,
              "onConfigurationChange() called");

        // Reset the WeakReference.
        mView = new WeakReference<>(view);

        // A runtime configuration change occurred.
        mConfigurationChangeOccurred = true;
    }

    /**
     * Returns true if a configuration change has ever occurred, else
     * false.
     */
    public boolean configurationChangeOccurred() {
        return mConfigurationChangeOccurred;
    }

    /**
     * Factory method that creates an Argv string containing the
     * options.
     */
    private String[] makeArgv(Intent intent) {
        // Create the list of arguments to pass to the Options
        // singleton.
        return new String[]{
                "-b", // Number of Being threads.
                intent.getStringExtra("BEINGS"),
                "-p", // Number of Palantiri.
                intent.getStringExtra("PALANTIRI"),
                "-i", // Gazing iterations.
                intent.getStringExtra("GAZING_ITERATIONS"),
        };
    }

    /**
     * Returns true if the simulation is currently running, else false.
     */
    public boolean isRunning() {
        return mRunning;
    }

    /**
     * Sets whether the simulation is currently running or not.
     */
    public void setRunning(boolean running) {
        mRunning = running;
    }

    /**
     * Returns the List of Palantiri and whether they are gazing.
     */
    public List<DotColor> getPalantiriColors() {
        return mPalantiriColors;
    }

    /**
     * Returns the List of Beings and whether they are gazing.
     */
    public List<DotColor> getBeingsColors() {
        return mBeingsColors;
    }

    /**
     * This method is called when the user asks to start the
     * simulation in the context of the main UI Thread.  It creates
     * the designated number of Palantiri and adds them to the
     * PalantiriManager.  It then creates a Thread for each Being and
     * has each Being attempt to acquire a Palantir for gazing,
     * mediated by the PalantiriManager.  The Threads call methods
     * from the MVP.RequiredViewOps interface to visualize what is
     * happening to the user.
     **/
    public void start() {
        // Initialize the Palantiri.
        getModel().makePalantiri(Options.instance().numberOfPalantiri());

        // Show the Beings on the UI.
        mView.get().showBeings();

        // Show the palantiri on the UI.
        mView.get().showPalantiri();

        // Create and execute an Thread for each Being and 
        // store the results in a list of futures.
        beingBeingCallablesGazing(Options.instance().numberOfBeings());

        // Spawn a thread that waits for all the futures to complete.
        awaitCompletionOfFutures();
    }

    /**
     * Create a List of Threads that will be used to represent the
     * Beings in this simulation.  Each Thread is passed a
     * BeingCallable parameter that takes the index of the Being in
     * the list as a parameter.
     * 
     * @param beingCount
     *            Number of Being Threads to create
     */
    private void beingBeingCallablesGazing(int beingCount) {
        // Create a new ArrayList that stores beingCount number of
        // BeingCallables and then create a cached thread pool to run
        // all these callables.
        // TODO - You fill in here.
        mBeingCallables = new ArrayList<BeingCallable>(beingCount);
        for (int i = 0; i <beingCount; i++) {
            BeingCallable call =  new BeingCallable(this);
            mBeingCallables.add(call);
        }
        mExecutor = Executors.newCachedThreadPool();
        // Call submitAll() to run all the BeingCallables concurrently
        // in the ExecutorService thread pool and store the results in
        // mFutureList.
        mFutureList = StreamsUtils.submitAll(mExecutor,
                                             mBeingCallables);


    }

    /**
     * Spawn a thread that waits for all the futures to complete.
     */
    private void awaitCompletionOfFutures() {
        // Use the ExecutorService to execute a runnable lambda that
        // uses a foreach loop to wait for all the futures to
        // complete.  After they're all complete then tell the UI
        // thread this simulation is done.  If an exception is thrown
        // shutdown the simulation.

        // TODO -- you fill in here.
        Runnable r1 = () -> {
            try {
                for (Future<?> f : mFutureList) {
                    f.get();
                }

            } catch (Exception e) {
                shutdown();
            } finally{
                mView.get().done();
            }
        };
        // TODO -- you fill in here.
        mExecutor.execute(r1);

    }

    /**
     * This method is called if an unrecoverable exception occurs or
     * the user explicitly stops the simulation.  It interrupts all
     * the other threads and notifies the UI.
     */
    public void shutdown() {
        synchronized(this) {
            // Cancel all the outstanding BeingCallables via their
            // futures.
            // TODO - you fill in here.
            for (Future<?> item: mFutureList){
                item.cancel(true);
            }

            // Inform the user that we're shutting down the
            // simulation due to an error.
            mView.get().shutdownOccurred(mBeingCallables.size());
        }
    }

    /**
     * Get a reference to the Model layer.
     */
    PalantiriModel getModel() {
        return mModel;
    }

    /**
     * Get a reference to the View layer.
     */
    GazingSimulationActivity getView() {
        return mView.get();
    }
}
