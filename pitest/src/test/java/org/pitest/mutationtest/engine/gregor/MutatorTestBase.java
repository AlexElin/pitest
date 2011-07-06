/*
 * Copyright 2010 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and limitations under the License. 
 */
package org.pitest.mutationtest.engine.gregor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.ASMifierClassVisitor;
import org.objectweb.asm.util.CheckClassAdapter;
import org.pitest.extension.Transformation;
import org.pitest.extension.common.ExcludedPrefixIsolationStrategy;
import org.pitest.functional.F;
import org.pitest.functional.FunctionalList;
import org.pitest.functional.predicate.Predicate;
import org.pitest.functional.predicate.True;
import org.pitest.internal.ClassPathByteArraySource;
import org.pitest.internal.IsolationUtils;
import org.pitest.internal.classloader.TransformingClassLoader;
import org.pitest.mutationtest.MutationDetails;
import org.pitest.mutationtest.engine.Mutant;
import org.pitest.util.Unchecked;

public abstract class MutatorTestBase {

  protected GregorMutater engine;

  protected FunctionalList<MutationDetails> findMutationsFor(
      final Class<?> clazz) {
    return this.engine.findMutations(Collections.singleton(clazz.getName()));
  }

  protected void createTesteeWith(final Predicate<MethodInfo> filter,
      final MethodMutatorFactory... mutators) {
    this.engine = new GregorMutater(new ClassPathByteArraySource(), filter,
        Arrays.asList(mutators), Collections.singletonList(Logger.class
            .getName()));
  }

  protected void createTesteeWith(final Predicate<MethodInfo> filter,
      final Collection<String> loggingClasses,
      final MethodMutatorFactory... mutators) {
    this.engine = new GregorMutater(new ClassPathByteArraySource(), filter,
        Arrays.asList(mutators), loggingClasses);
  }

  protected void createTesteeWith(final MethodMutatorFactory... mutators) {
    createTesteeWith(True.<MethodInfo> all(), mutators);
  }

  protected void assertMutantCallableReturns(final Callable<String> unmutated,
      final Mutant mutant, final String expected) throws Exception {
    assertEquals(expected, mutateAndCall(unmutated, mutant));
  }

  protected String mutateAndCall(final Callable<String> unmutated,
      final Mutant mutant) {
    try {
      final ClassLoader loader = createClassLoader(mutant);
      return runInClassLoader(loader, unmutated);
    } catch (final Exception ex) {
      throw Unchecked.translateCheckedException(ex);
    }
  }

  private ClassLoader createClassLoader(final Mutant mutant) throws Exception {
    final TransformingClassLoader loader = new TransformingClassLoader(
        createTransformation(mutant), new ExcludedPrefixIsolationStrategy());

    return loader;
  }

  private Transformation createTransformation(final Mutant mutant) {
    return new Transformation() {

      public byte[] transform(final String name, final byte[] bytes) {
        if (name.equals(mutant.getDetails().getClazz())) {
          return mutant.getBytes();
        } else {
          return bytes;
        }
      }

    };
  }

  @SuppressWarnings("unchecked")
  private String runInClassLoader(final ClassLoader loader,
      final Callable<String> callable) throws Exception {
    final Callable<String> c = (Callable<String>) IsolationUtils
        .cloneForLoader(callable, loader);
    return c.call();

  }

  protected FunctionalList<Mutant> getMutants(
      final FunctionalList<MutationDetails> details) {
    return details.map(createMutant());
  }

  private F<MutationDetails, Mutant> createMutant() {
    return new F<MutationDetails, Mutant>() {

      public Mutant apply(final MutationDetails a) {
        return MutatorTestBase.this.engine.getMutation(a.getId());
      }

    };
  }

  protected Mutant getFirstMutant(final Collection<MutationDetails> actual) {
    assertFalse("No mutant found", actual.isEmpty());
    final Mutant mutant = this.engine.getMutation(actual.iterator().next()
        .getId());
    verifyMutant(mutant);
    return mutant;
  }

  protected Mutant getFirstMutant(final Class<?> mutee) {
    final Collection<MutationDetails> actual = findMutationsFor(mutee);
    return getFirstMutant(actual);
  }

  private void verifyMutant(final Mutant mutant) {
    // printMutant(mutant);
    final StringWriter sw = new StringWriter();
    final PrintWriter pw = new PrintWriter(sw);
    CheckClassAdapter.verify(new ClassReader(mutant.getBytes()), false, pw);
    assertTrue(sw.toString(), sw.toString().length() == 0);

  }

  protected void printMutant(final Mutant mutant) {
    final ASMifierClassVisitor asm = new ASMifierClassVisitor(new PrintWriter(
        System.out));
    final ClassReader r = new ClassReader(mutant.getBytes());
    r.accept(asm, ClassReader.SKIP_FRAMES);
  }

  protected void assertMutantsReturn(final Callable<String> mutee,

  final FunctionalList<MutationDetails> details,
      final String... expectedResults) {

    final FunctionalList<Mutant> mutants = this.getMutants(details);
    assertEquals("Should return one mutant for each request", details.size(),
        mutants.size());
    final FunctionalList<String> results = mutants
        .map(mutantToStringReults(mutee));

    int i = 0;
    for (final String actual : results) {
      assertEquals(expectedResults[i], actual);
      i++;
    }
  }

  private F<Mutant, String> mutantToStringReults(final Callable<String> mutee) {
    return new F<Mutant, String>() {

      public String apply(final Mutant mutant) {
        return mutateAndCall(mutee, mutant);
      }

    };
  }

  protected void assertMutantsAreFrom(
      final FunctionalList<MutationDetails> actualDetails,
      final Class<?>... mutators) {
    assertEquals(mutators.length, actualDetails.size());
    int i = 0;
    for (final MutationDetails each : actualDetails) {
      assertEquals(each.getId().getMutator(), mutators[i].getName());
      i++;
    }
  }

  protected Mutant createFirstMutant(
      final Class<? extends Callable<String>> mutee) {
    final Collection<MutationDetails> actual = findMutationsFor(mutee);
    return getFirstMutant(actual);
  }

  protected Predicate<MethodInfo> mutateOnlyCallMethod() {
    return new Predicate<MethodInfo>() {

      public Boolean apply(final MethodInfo a) {
        return a.getName().equals("call");
      }

    };
  }

  protected F<MutationDetails, Boolean> descriptionContaining(final String value) {
    return new F<MutationDetails, Boolean>() {
      public Boolean apply(final MutationDetails a) {
        return a.getDescription().contains(value);
      }
    };
  }

}