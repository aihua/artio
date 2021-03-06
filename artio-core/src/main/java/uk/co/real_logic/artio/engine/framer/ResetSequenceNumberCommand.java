/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;

import java.util.function.LongToIntFunction;

import static uk.co.real_logic.artio.Reply.State.COMPLETED;
import static uk.co.real_logic.artio.Reply.State.ERRORED;

class ResetSequenceNumberCommand implements Reply<Void>, AdminCommand
{
    private volatile State state = State.EXECUTING;
    // write to error only when updating state
    private Exception error;

    private final long sessionId;

    // State to only be accessed on the Framer thread.
    private final GatewaySessions gatewaySessions;
    private final SessionContexts sessionContexts;
    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final SequenceNumberIndexReader sentSequenceNumberIndex;
    private final GatewayPublication inboundPublication;
    private final GatewayPublication outboundPublication;
    private Session session;
    private LongToIntFunction libraryLookup;
    private long waitSequence = 1;

    void libraryLookup(final LongToIntFunction libraryLookup)
    {
        this.libraryLookup = libraryLookup;
    }

    private enum Step
    {
        START,

        // Sending the reset session message - used if engine managed session
        RESET_ENGINE_SESSION,

        // Sending the reset session message - used if library managed session
        RESET_LIBRARY_SESSION,

        // Send the message to reset sent seq num - used if not logged in
        RESET_SENT,

        // Send the message to reset recv seq num - used if not logged in
        RESET_RECV,

        // await reset of sent seq num - jumped to from RESET_ENGINE_SESSION and RESET_LIBRARY_SESSION
        // since this will be updated when the other end of the session acknowledges the sequence reset.
        AWAIT_RECV,

        // await reset of recv seq num
        AWAIT_SENT,

        DONE
    }

    private Step step = Step.START;

    // Variables initialised on any thread, but objects only executed on the Framer thread
    // so they don't all have to be thread safe
    ResetSequenceNumberCommand(
        final long sessionId,
        final GatewaySessions gatewaySessions,
        final SessionContexts sessionContexts,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication)
    {
        this.sessionId = sessionId;
        this.gatewaySessions = gatewaySessions;
        this.sessionContexts = sessionContexts;
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
        this.sentSequenceNumberIndex = sentSequenceNumberIndex;
        this.inboundPublication = inboundPublication;
        this.outboundPublication = outboundPublication;
    }

    public Exception error()
    {
        return error;
    }

    private void onError(final Exception error)
    {
        this.error = error;
        state = ERRORED;
    }

    public Void resultIfPresent()
    {
        return null;
    }

    public State state()
    {
        return state;
    }

    public void execute(final Framer framer)
    {
        framer.onResetSequenceNumber(this);
    }

    // Only to be called on the Framer thread.
    boolean poll()
    {
        switch (step)
        {
            case START:
            {
                if (sessionIsUnknown())
                {
                    onError(new IllegalArgumentException(
                        String.format("Unknown sessionId: %d", sessionId)));

                    return true;
                }

                final GatewaySession gatewaySession = gatewaySessions.sessionById(sessionId);
                // Engine Managed
                if (gatewaySession != null)
                {
                    session = gatewaySession.session();
                    step = Step.RESET_ENGINE_SESSION;
                }
                // Library Managed
                else if (isAuthenticated())
                {
                    step = Step.RESET_LIBRARY_SESSION;
                }
                // Not logged in
                else
                {
                    sessionContexts.sequenceReset(sessionId);
                    step = Step.RESET_RECV;
                }

                return false;
            }

            case RESET_ENGINE_SESSION:
            {
                final long position = session.resetSequenceNumbers();
                if (!Pressure.isBackPressured(position))
                {
                    waitSequence = 1;
                    step = Step.AWAIT_RECV;
                }
                return false;
            }

            case RESET_LIBRARY_SESSION:
            {
                if (isAuthenticated())
                {
                    final int libraryId = libraryLookup.applyAsInt(sessionId);
                    if (!Pressure.isBackPressured(
                        inboundPublication.saveResetLibrarySequenceNumber(libraryId, sessionId)))
                    {
                        waitSequence = 1;
                        step = Step.AWAIT_RECV;
                    }
                }
                else
                {
                    // The session disconnects whilst you're trying to talk to reset it
                    step = Step.START;
                }

                return false;
            }

            case RESET_RECV:
                waitSequence = 0;
                return reset(inboundPublication, Step.RESET_SENT);

            case RESET_SENT:
                waitSequence = 0;
                return reset(outboundPublication, Step.AWAIT_RECV);

            case AWAIT_RECV:
                return await(receivedSequenceNumberIndex);

            case AWAIT_SENT:
                return await(sentSequenceNumberIndex);

            case DONE:
                return true;
        }

        return false;
    }

    private boolean isAuthenticated()
    {
        return sessionContexts.isAuthenticated(sessionId);
    }

    private boolean reset(final GatewayPublication publication, final Step nextStep)
    {
        if (!Pressure.isBackPressured(publication.saveResetSequenceNumber(sessionId)))
        {
            step = nextStep;
        }

        return false;
    }

    private boolean await(final SequenceNumberIndexReader sequenceNumberIndex)
    {
        final boolean done = sequenceNumberIndex.lastKnownSequenceNumber(sessionId) <= waitSequence;
        if (done)
        {
            step = Step.DONE;
            state = COMPLETED;
        }
        return done;
    }

    private boolean sessionIsUnknown()
    {
        return !sessionContexts.isKnownSessionId(sessionId);
    }

    public String toString()
    {
        return "ResetSequenceNumberReply{" +
            "state=" + state +
            ", error=" + error +
            ", sessionId=" + sessionId +
            ", step=" + step +
            '}';
    }
}
