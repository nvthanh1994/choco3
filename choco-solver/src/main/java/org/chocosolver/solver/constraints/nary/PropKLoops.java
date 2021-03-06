/**
 * Copyright (c) 2014,
 *       Charles Prud'homme (TASC, INRIA Rennes, LINA CNRS UMR 6241),
 *       Jean-Guillaume Fages (COSLING S.A.S.).
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.chocosolver.solver.constraints.nary;

import gnu.trove.map.hash.THashMap;
import org.chocosolver.memory.IEnvironment;
import org.chocosolver.memory.IStateInt;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.constraints.Propagator;
import org.chocosolver.solver.constraints.PropagatorPriority;
import org.chocosolver.solver.exception.ContradictionException;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.util.ESat;
import org.chocosolver.util.objects.setDataStructures.ISet;
import org.chocosolver.util.objects.setDataStructures.SetFactory;
import org.chocosolver.util.objects.setDataStructures.SetType;
import org.chocosolver.util.tools.ArrayUtils;

/**
 * Incremental propagator which restricts the number of loops:
 * |{succs[i]=i+offSet}| = nbLoops
 *
 * @author Jean-Guillaume Fages
 */
public class PropKLoops extends Propagator<IntVar> {

    //***********************************************************************************
    // VARIABLES
    //***********************************************************************************

    // number of nodes
    private int n;
    // offset (usually 0 but 1 with MiniZinc)
    private int offSet;
    // uninstantiated variables that can be loops
    private ISet possibleLoops;
    private IStateInt nbMinLoops;

    //***********************************************************************************
    // CONSTRUCTORS
    //***********************************************************************************

	/**
	 * Incremental propagator which restricts the number of loops:
	 * |{succs[i]=i+offSet}| = nbLoops
	 *
	 * @param succs array of integer variables
	 * @param offSet offset
	 * @param nbLoops integer variable
	 */
	public PropKLoops(IntVar[] succs, int offSet, IntVar nbLoops) {
		super(ArrayUtils.append(succs, new IntVar[]{nbLoops}), PropagatorPriority.UNARY, true);
		this.n = succs.length;
		this.offSet = offSet;
		IEnvironment environment = solver.getEnvironment();
		this.possibleLoops = SetFactory.makeStoredSet(SetType.BIPARTITESET, n, solver);
		this.nbMinLoops = environment.makeInt();
	}

    //***********************************************************************************
    // METHODS
    //***********************************************************************************

    @Override
    public void propagate(int evtmask) throws ContradictionException {
        possibleLoops.clear();
        nbMinLoops.set(0);
        for (int i = 0; i < n; i++) {
            if (vars[i].contains(i + offSet)) {
                if (vars[i].isInstantiated()) {
                    nbMinLoops.add(1);
                } else {
                    possibleLoops.add(i);
                }
            }
        }
        filter();
    }

    private void filter() throws ContradictionException {
        int nbMin = nbMinLoops.get();
        int nbMax = nbMin + possibleLoops.getSize();
        vars[n].updateLowerBound(nbMin, aCause);
        vars[n].updateUpperBound(nbMax, aCause);
        if (vars[n].isInstantiated() && nbMin != nbMax) {
            if (vars[n].getValue() == nbMax) {
                for (int i = possibleLoops.getFirstElement(); i >= 0; i = possibleLoops.getNextElement()) {
                    vars[i].instantiateTo(i + offSet, aCause);
                    assert vars[i].isInstantiatedTo(i + offSet);
                    nbMinLoops.add(1);
                }
                possibleLoops.clear();
                setPassive();
            } else if (vars[n].getValue() == nbMin) {
                for (int i = possibleLoops.getFirstElement(); i >= 0; i = possibleLoops.getNextElement()) {
                    if (vars[i].removeValue(i + offSet, aCause)) {
                        possibleLoops.remove(i);
                    }
                }
                if (possibleLoops.isEmpty()) {
                    setPassive();
                }
            }
        }
    }

    @Override
    public void propagate(int idV, int mask) throws ContradictionException {
        if (idV < n) {
            if (possibleLoops.contain(idV)) {
                if (vars[idV].contains(idV + offSet)) {
                    if (vars[idV].isInstantiated()) {
                        nbMinLoops.add(1);
                        possibleLoops.remove(idV);
                    }
                } else {
                    possibleLoops.remove(idV);
                }
            }
        }
        filter();
    }

    @Override
    public ESat isEntailed() {
        int nbMax = 0;
        int nbMin = 0;
        for (int i = 0; i < n; i++) {
            if (vars[i].contains(i + offSet)) {
                nbMax++;
                if (vars[i].isInstantiated()) {
                    nbMin++;
                }
            }
        }
        if (vars[n].getLB() > nbMax || vars[n].getUB() < nbMin) {
            return ESat.FALSE;
        }
        if (nbMin == nbMax && vars[n].isInstantiated()) {
            return ESat.TRUE;
        }
        return ESat.UNDEFINED;
    }

    @Override
    public void duplicate(Solver solver, THashMap<Object, Object> identitymap) {
        if (!identitymap.containsKey(this)) {
            int size = this.vars.length - 1;
            IntVar[] aVars = new IntVar[size];
            for (int i = 0; i < size; i++) {
                this.vars[i].duplicate(solver, identitymap);
                aVars[i] = (IntVar) identitymap.get(this.vars[i]);
            }
            this.vars[size].duplicate(solver, identitymap);
            IntVar aVar = (IntVar) identitymap.get(this.vars[size]);
            identitymap.put(this, new PropKLoops(aVars, this.offSet, aVar));
        }
    }
}
